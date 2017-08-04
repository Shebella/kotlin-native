package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.common.ir.cfg.bitcode.CfgToBitcode
import org.jetbrains.kotlin.backend.common.ir.cfg.bitcode.emitBitcodeFromCfg
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.isIntrinsic
import org.jetbrains.kotlin.backend.konan.descriptors.isUnit
import org.jetbrains.kotlin.backend.konan.isValueType
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isPrimitiveNumberType
import org.jetbrains.kotlin.util.OperatorNameConventions

//-----------------------------------------------------------------------------//

internal class CfgSelector(override val context: Context): IrElementVisitorVoid, TypeResolver {

    private val ir = Ir()

    private var currentLandingBlock: Block? = null
    private var currentFunction = Function("Outer")
    private var currentBlock    = currentFunction.enter

    private val globalInitFunction: Function = Function("global-init").also {
        ir.addFunction(it)
    }
    private var currentGlobalInitBlock: Block = globalInitFunction.enter

    private val variableMap = mutableMapOf<ValueDescriptor, Operand>()
    private val loopStack   = mutableListOf<LoopLabels>()

    //-------------------------------------------------------------------------//

    private fun newVariable(type: Type, name: String = currentFunction.genVariableName())
            = Variable(type, name)

    //-------------------------------------------------------------------------//

    private fun newBlock(name: String = "block") = currentFunction.newBlock(name)

    //-------------------------------------------------------------------------//

    private val operatorToOpcode = mapOf(
            OperatorNameConventions.PLUS  to BinOp::Add,
            OperatorNameConventions.MINUS to BinOp::Sub,
            OperatorNameConventions.TIMES to BinOp::Mul,
            OperatorNameConventions.DIV   to BinOp::Sdiv,
            OperatorNameConventions.MOD   to BinOp::Srem
    )

    private data class LoopLabels(val loop: IrLoop, val check: Block, val exit: Block)

    //-------------------------------------------------------------------------//

    fun select() {
        context.irModule!!.acceptVoid(this)
        currentGlobalInitBlock.inst(Ret())

        context.log { ir.log(); "" }

        emitBitcodeFromCfg(context)

        CfgToBitcode(
                ir,
                context
        ).select()
    }

    //-------------------------------------------------------------------------//

    override fun visitClass(declaration: IrClass) {
        val klass = declaration.descriptor.cfgKlass
        ir.addKlass(klass)
        declaration.declarations.forEach {
            it.acceptVoid(this)
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitField(declaration: IrField) {
        val descriptor = declaration.descriptor

        val containingClass = descriptor.containingClass

        if (containingClass == null) {
            declaration.initializer?.expression?.let {
                val initValue = selectStatement(it)
                val fieldName = declaration.descriptor.toCfgName()
                val globalPtr = Constant(descriptor.type.cfgType, fieldName)                                  // TODO should we use special type here?
                currentGlobalInitBlock.inst(Store(initValue, globalPtr, Cfg0))
            }
        }

    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        if (declaration.origin != IrDeclarationOrigin.FAKE_OVERRIDE) {
            selectFunction(declaration)
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectFunction(irFunction: IrFunction) {

        currentFunction = irFunction.descriptor.cfgFunction

        ir.addFunction(currentFunction)

        irFunction.descriptor.allParameters
            .forEach {
                variableMap[it] = Variable(it.type.cfgType, it.name.asString())
            }

        irFunction.body?.let {
            currentBlock = currentFunction.enter
            currentLandingBlock = null
            when (it) {
                is IrExpressionBody -> selectStatement(it.expression)
                is IrBlockBody -> it.statements.forEach { statement ->
                    selectStatement(statement)
                }
                else -> throw TODO("unsupported function body type: $it")
            }
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectStatement(statement: IrStatement): Operand =
        when (statement) {
            is IrTypeOperatorCall          -> selectTypeOperatorCall         (statement)
            is IrCall                      -> selectCall                     (statement)
            is IrDelegatingConstructorCall -> selectDelegatingConstructorCall(statement)
            is IrContainerExpression       -> selectContainerExpression      (statement)
            is IrConst<*>                  -> selectConst                    (statement)
            is IrWhileLoop                 -> selectWhileLoop                (statement)
            is IrDoWhileLoop               -> selectDoWhileLoop              (statement)
            is IrBreak                     -> selectBreak                    (statement)
            is IrContinue                  -> selectContinue                 (statement)
            is IrReturn                    -> selectReturn                   (statement)
            is IrWhen                      -> selectWhen                     (statement)
            is IrSetVariable               -> selectSetVariable              (statement)
            is IrVariable                  -> selectVariable                 (statement)
            is IrVariableSymbol            -> selectVariableSymbol           (statement)
            is IrValueSymbol               -> selectValueSymbol              (statement)
            is IrGetValue                  -> selectGetValue                 (statement)
            is IrVararg                    -> selectVararg                   (statement)
            is IrThrow                     -> selectThrow                    (statement)
            is IrTry                       -> selectTry                      (statement)
            is IrGetField                  -> selectGetField                 (statement)
            is IrSetField                  -> selectSetField                 (statement)
            is IrGetObjectValue            -> selectGetObjectValue           (statement)
            is IrInstanceInitializerCall   -> selectInstanceInitializerCall  (statement)
            else -> {
                println("ERROR: Not implemented yet: $statement")
                CfgNull
            }
        }

    //-------------------------------------------------------------------------//

    private fun selectInstanceInitializerCall(statement: IrInstanceInitializerCall): Operand {
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectGetObjectValue(statement: IrGetObjectValue): Operand {
        if (statement.descriptor.isUnit()) {
            return CfgUnit
        }
        val initBlock = newBlock("label_init")
        val continueBlock = newBlock("label_continue")

        val address = Constant(Type.ptr(), "FIXME")
        val objectVal = currentBlock.inst(Load(newVariable(statement.type.cfgType), address, Cfg0))
        val condition = currentBlock.inst(BinOp.IcmpNE(newVariable(Type.boolean), objectVal, CfgNull))
        currentBlock.inst(Condbr(condition, continueBlock, initBlock))

        currentBlock = initBlock
        // TODO: create object

        currentBlock = continueBlock
        return objectVal
    }

    //-------------------------------------------------------------------------//

    private fun selectGetField(statement: IrGetField): Operand {
        val fieldType = newVariable(statement.type.cfgType)
        val receiver = statement.receiver
        return if (receiver != null) {                                                      // It is class field.
            val thisPtr   = selectStatement(receiver)                                       // Get object pointer.
            val thisType  = receiver.type.cfgType as? Type.KlassPtr
                    ?: error("selecting GetFild on primitive type")                         // Get object type.
            val offsetVal = thisType.fieldOffset(statement.descriptor.toCfgName())          // Calculate field offset inside the object.
            val offset    = Constant(Type.int, offsetVal)                                    //
            currentBlock.inst(Load(fieldType, thisPtr, offset))                                   // TODO make "load" receiving offset as Int
        } else {                                                                            // It is global field.
            val fieldName = statement.descriptor.toCfgName()
            val globalPtr = Constant(Type.FieldPtr, fieldName)                                  // TODO should we use special type here?
            currentBlock.inst(Load(fieldType, globalPtr, Cfg0))
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectSetField(statement: IrSetField): Operand {
        val value     = selectStatement(statement.value)                                    // Value to store in filed.
        val receiver  = statement.receiver                                                  // Object holding the field.
        if (receiver != null) {                                                             //
            val thisPtr = selectStatement(receiver)                                         // Pointer to the object.
            val thisType  = receiver.type.cfgType as? Type.KlassPtr
                    ?: error("selecting GetFild on primitive type")                                    // Get object type.
            val offsetVal = thisType.fieldOffset(statement.descriptor.toCfgName())          // Calculate field offset inside the object.
            val offset    = Constant(Type.int, offsetVal)                                    //
            currentBlock.inst(Store(value, thisPtr, offset))                                      // TODO make "load" receiving offset as Int
        } else {                                                                            // It is global field.
            val fieldName = statement.descriptor.toCfgName()
            val globalPtr = Constant(Type.FieldPtr, fieldName)                                  // TODO should we use special type here?
            currentBlock.inst(Store(value, globalPtr, Cfg0))
        }
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectConst(const: IrConst<*>): Constant =
        when(const.kind) {
            IrConstKind.Null    -> CfgNull
            IrConstKind.Boolean -> Constant(Type.boolean, const.value as Boolean)
            IrConstKind.Byte    -> Constant(Type.byte,    const.value as Byte)
            IrConstKind.Short   -> Constant(Type.short,   const.value as Short)
            IrConstKind.Int     -> Constant(Type.int,     const.value as Int)
            IrConstKind.Long    -> Constant(Type.long,    const.value as Long)
            IrConstKind.Float   -> Constant(Type.float,   const.value as Float)
            IrConstKind.Double  -> Constant(Type.double,  const.value as Double)
            IrConstKind.Char    -> Constant(Type.char,    const.value as Char)
            IrConstKind.String  -> Constant(TypeString,   const.value as String)
        }

    //-------------------------------------------------------------------------//

    private fun selectTypeOperatorCall(statement: IrTypeOperatorCall): Operand =
        when (statement.operator) {
            IrTypeOperator.CAST                      -> selectCast           (statement)
            IrTypeOperator.IMPLICIT_INTEGER_COERCION -> selectIntegerCoercion(statement)
            IrTypeOperator.IMPLICIT_CAST             -> selectImplicitCast   (statement)
            IrTypeOperator.IMPLICIT_NOTNULL          -> selectImplicitNotNull(statement)
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> selectCoercionToUnit (statement)
            IrTypeOperator.SAFE_CAST                 -> selectSafeCast       (statement)
            IrTypeOperator.INSTANCEOF                -> selectInstanceOf     (statement)
            IrTypeOperator.NOT_INSTANCEOF            -> selectNotInstanceOf  (statement)
        }

    //-------------------------------------------------------------------------//

    private fun selectCast(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type  = statement.typeOperand.cfgType
        return currentBlock.inst(Cast(newVariable(type), value))
    }

    //-------------------------------------------------------------------------//

    private fun KotlinType.isPrimitiveInteger(): Boolean {
        return isPrimitiveNumberType() &&
                !KotlinBuiltIns.isFloat(this) &&
                !KotlinBuiltIns.isDouble(this) &&
                !KotlinBuiltIns.isChar(this)
    }

    //-------------------------------------------------------------------------//

    private fun selectIntegerCoercion(statement: IrTypeOperatorCall): Operand {
        val type = statement.typeOperand
        assert(type.isPrimitiveInteger())
        val value = selectStatement(statement.argument)
        val srcType = value.type
        val dstType = type.cfgType
        val srcWidth = srcType.byteSize
        val dstWidth = dstType.byteSize
        return when {
            srcWidth == dstWidth -> value
            srcWidth >  dstWidth -> currentBlock.inst(Trunk(newVariable(dstType), value))
            else                 -> currentBlock.inst(Sext(newVariable(dstType), value))                     // srcWidth < dstWidth
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectImplicitCast(statement: IrTypeOperatorCall): Operand = selectStatement(statement.argument)

    //-------------------------------------------------------------------------//

    private fun selectImplicitNotNull(statement: IrTypeOperatorCall): Operand {
        println("ERROR: Not implemented yet: selectImplicitNotNull") // Also it's not implemented in IrToBitcode.
        return Variable(Type.int, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectCoercionToUnit(statement: IrTypeOperatorCall): Operand {
        selectStatement(statement.argument)
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectSafeCast(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type  = statement.typeOperand.cfgType
        return currentBlock.inst(Cast(newVariable(type), value))
    }

    //-------------------------------------------------------------------------//

    private fun selectInstanceOf(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type = statement.typeOperand.cfgType                                        // Class to compare.
        return currentBlock.inst(InstanceOf(newVariable(Type.boolean), value, type))
    }

    //-------------------------------------------------------------------------//

    private fun selectNotInstanceOf(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type = statement.typeOperand.cfgType                                        // Class to compare.
        return currentBlock.inst(NotInstanceOf(newVariable(Type.boolean), value, type))
    }

    //-------------------------------------------------------------------------//

    private fun IrCall.hasOpcode() =
            descriptor.isOperator &&
            descriptor.name in operatorToOpcode &&
            dispatchReceiver?.type?.isValueType() ?: false &&
            descriptor.valueParameters.all { it.type.isValueType() }

    //-------------------------------------------------------------------------//

    private fun selectCall(irCall: IrCall): Operand {
        return when {
            irCall.descriptor.isIntrinsic -> selectIntrinsicCall(irCall)
            irCall.hasOpcode() -> selectOperator(irCall)
            irCall.descriptor is ConstructorDescriptor -> selectConstructorCall(irCall)
            else -> {
                val args = irCall.getArguments().map { (_, expr) -> selectStatement(expr) }
                generateCall(irCall.descriptor, irCall.type.cfgType, args)
            }
        }
    }

    //-------------------------------------------------------------------------//

    // TODO: maybe intrinsics should be replaced with special instructions in CFG
    private fun selectIntrinsicCall(irCall: IrCall): Operand {
        val args = irCall.getArguments().map { (_, expr) -> selectStatement(expr) }
        val descriptor = irCall.descriptor.original
        val name = descriptor.fqNameUnsafe.asString()

        when (name) {
            "konan.internal.areEqualByValue" -> {
                val arg0 = args[0]
                val arg1 = args[1]
                return when (arg0.type) {
                    Type.float, Type.double ->
                        currentBlock.inst(BinOp.FcmpEq(newVariable(Type.boolean), arg0, arg1))
                    else ->
                        currentBlock.inst(BinOp.IcmpEq(newVariable(Type.boolean), arg0, arg1))
                }
            }
            "konan.internal.getContinuation" -> return CfgUnit // coroutines are not supported yet
        }

        val interop = context.interopBuiltIns
        return when (descriptor) {
            interop.interpretNullablePointed, interop.interpretCPointer,
            interop.nativePointedGetRawPointer, interop.cPointerGetRawValue -> args.single()

            in interop.readPrimitive -> {
                val pointerType = descriptor.returnType!!.cfgType // pointerType(codegen.getLLVMType(descriptor.returnType!!))
                val rawPointer = args.last()
                val pointer = currentBlock.inst(Bitcast(newVariable(pointerType), rawPointer, pointerType))
                currentBlock.inst(Load(newVariable(pointerType), pointer, Cfg0))
            }
            in interop.writePrimitive -> {
                val pointerType = descriptor.valueParameters.last().type.cfgType //pointerType(codegen.getLLVMType(descriptor.valueParameters.last().type))
                val rawPointer = args[1]
                val pointer = currentBlock.inst(Bitcast(newVariable(pointerType), rawPointer, pointerType))
                currentBlock.inst(Store(args[2], pointer, Cfg0))
                CfgUnit
            }
            context.builtIns.nativePtrPlusLong -> currentBlock.inst(Gep(newVariable(Type.ptr()), args[0], args[1]))
            context.builtIns.getNativeNullPtr -> Constant(Type.ptr(), CfgNull)
            interop.getPointerSize -> Constant(Type.int, Type.ptr().byteSize)
            context.builtIns.nativePtrToLong -> {
                val intPtrValue = Constant(Type.ptr(), args.single()) // codegen.ptrToInt(args.single(), codegen.intPtrType)
                val resultType = descriptor.returnType!!.cfgType

                if (resultType == intPtrValue.type) {
                    intPtrValue
                } else {
                    currentBlock.inst(Sext(newVariable(resultType), intPtrValue))
                }
            }
            else -> TODO(descriptor.toString())
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectConstructorCall(irCall: IrCall) : Operand {
        assert(irCall.descriptor is ConstructorDescriptor)
        // allocate memory for the instance
        // call init
        val descriptor = irCall.descriptor as ConstructorDescriptor
        val constructedClass = descriptor.constructedClass
        val objPtr = currentBlock.inst(Alloc(newVariable(irCall.type.cfgType), constructedClass.cfgKlass))
        val args = mutableListOf(objPtr).apply {
            irCall.getArguments().mapTo(this) { (_, expr) -> selectStatement(expr) }
        }
        return generateCall(irCall.descriptor, irCall.type.cfgType, args)
    }

    //-------------------------------------------------------------------------//

    private fun selectDelegatingConstructorCall(irCall: IrDelegatingConstructorCall): Operand {
        val args = mutableListOf<Operand>(currentFunction.parameters[0]).apply {
            irCall.getArguments().mapTo(this) { (_, expr) -> selectStatement(expr) }
        }
        return generateCall(irCall.descriptor, irCall.type.cfgType, args)
    }

    //-------------------------------------------------------------------------//

    private fun generateCall(descriptor: FunctionDescriptor, type: Type, args: List<Operand>): Operand {

        val callee = descriptor.cfgFunction

        val instruction = if (currentLandingBlock != null) {                    // We're inside try block.
            Invoke(callee, newVariable(type), args, currentLandingBlock!!)
        } else {
            Call(callee, newVariable(type), args)
        }
        return currentBlock.inst(instruction)
    }

    //-------------------------------------------------------------------------//

    private fun selectOperator(irCall: IrCall): Operand {
        val uses = irCall.getArguments().map { selectStatement(it.second) }
        val type = irCall.type.cfgType
        val def = newVariable(type)
        val binOp = operatorToOpcode[irCall.descriptor.name]?.invoke(def, uses[0], uses[1])
                ?: throw IllegalArgumentException("No opcode for call: $irCall")
        return currentBlock.inst(binOp)
    }

    //-------------------------------------------------------------------------//

    private fun selectContainerExpression(expression: IrContainerExpression): Operand {
        expression.statements.dropLast(1).forEach {
            selectStatement(it)
        }
        return expression.statements.lastOrNull()
            ?.let { selectStatement(it) } ?: CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectWhileLoop(irWhileLoop: IrWhileLoop): Operand {
        val loopCheck = newBlock("loop_check")
        val loopBody = newBlock("loop_body")
        val loopExit = newBlock("loop_exit")

        loopStack.push(LoopLabels(irWhileLoop, loopCheck, loopExit))

        currentBlock.inst(Br(loopCheck))
        currentBlock = loopCheck
        currentBlock.inst(Condbr(selectStatement(irWhileLoop.condition), loopBody, loopExit))

        currentBlock = loopBody
        irWhileLoop.body?.let { selectStatement(it) }
        if (!currentBlock.isLastInstructionTerminal()) {
            currentBlock.inst(Br(loopCheck))
        }
        loopStack.pop()
        currentBlock = loopExit
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectDoWhileLoop(loop: IrDoWhileLoop): Operand {
        val loopCheck = newBlock("loop_check")
        val loopBody = newBlock("loop_body")
        val loopExit = newBlock("loop_exit")

        loopStack.push(LoopLabels(loop, loopCheck, loopExit))

        currentBlock.inst(Br(loopBody))
        currentBlock = loopBody
        loop.body?.let { selectStatement(it) }
        if (!currentBlock.isLastInstructionTerminal()) {
            currentBlock.inst(Br(loopCheck))
        }

        currentBlock = loopCheck
        currentBlock.inst(Condbr(selectStatement(loop.condition), loopBody, loopExit))

        loopStack.pop()
        currentBlock = loopExit
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectBreak(expression: IrBreak): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
            .let { (_, _, exit) ->
                currentBlock.inst(Br(exit))
                Unit
            }
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectContinue(expression: IrContinue): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
            .let { (_, check, _) ->
                currentBlock.inst(Br(check))
                Unit
            }
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectReturn(irReturn: IrReturn): Operand {
        val target = irReturn.returnTarget
        val evaluated = selectStatement(irReturn.value)
        currentBlock.inst(Ret(evaluated))
        return if (target.returnsUnit()) {
            CfgNull
        } else {
            evaluated
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectWhen(expression: IrWhen): Operand {
        val resultVar = if (expression.type == context.builtIns.unitType) {
            null
        } else {
            newVariable(expression.type.cfgType)
        }
        val exitBlock = newBlock()

        expression.branches.forEach {
            val nextBlock = if (it == expression.branches.last()) exitBlock else newBlock()
            selectWhenClause(it, nextBlock, exitBlock, resultVar)
        }

        currentBlock = exitBlock
        return resultVar ?: CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectWhenClause(irBranch: IrBranch, nextBlock: Block, exitBlock: Block, variable: Variable?) {
        currentBlock = if (isUnconditional(irBranch)) {
            currentBlock
        } else {
            newBlock().also {
                currentBlock.inst(Condbr(selectStatement(irBranch.condition), it, nextBlock))
            }
        }

        val clauseExpr = selectStatement(irBranch.result)
        if (!currentBlock.isLastInstructionTerminal()) {
            variable?.let {
                currentBlock.inst(Mov(it, clauseExpr))
                Unit
            }
            currentBlock.inst(Br(exitBlock))
        }
        currentBlock = nextBlock
    }

    //-------------------------------------------------------------------------//

    private fun selectSetVariable(irSetVariable: IrSetVariable): Operand {
        val operand = selectStatement(irSetVariable.value)
        variableMap[irSetVariable.descriptor] = operand
        val variable = Variable(irSetVariable.value.type.cfgType, irSetVariable.descriptor.name.asString())
        currentBlock.inst(Mov(variable, operand))
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectVariable(irVariable: IrVariable): Operand {
        val operand = irVariable.initializer?.let { selectStatement(it) } ?: CfgNull
        variableMap[irVariable.descriptor] = operand
        return operand
    }

    //-------------------------------------------------------------------------//

    private fun selectVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand
        = variableMap[irVariableSymbol.descriptor] ?: CfgNull

    //-------------------------------------------------------------------------//

    private fun selectValueSymbol(irValueSymbol: IrValueSymbol): Operand
        = variableMap[irValueSymbol.descriptor] ?: CfgNull

    //-------------------------------------------------------------------------//

    private fun selectGetValue(getValue: IrGetValue): Operand
        = variableMap[getValue.descriptor] ?: CfgNull

    //-------------------------------------------------------------------------//

    private fun selectVararg(irVararg: IrVararg): Operand {
        val elements = irVararg.elements.map {
            if (it is IrExpression) {
                return@map selectStatement(it)
            }
            throw IllegalStateException("IrVararg neither was lowered nor can be statically evaluated")
        }
        // TODO: replace with a correct array type
        return Constant(Type.KlassPtr(Klass("Array")), elements)
    }

    //-------------------------------------------------------------------------//
    // Returns first catch block
    private fun selectCatches(irCatches: List<IrCatch>, tryExit: Block): Block {
        val prevBlock = currentBlock

        val header = newBlock("catch_header")
        val exception = Variable(Type.ptr(), "exception")

        // TODO: should expand to real exception object extraction
        header.inst(Landingpad(exception))
        currentBlock = header
        irCatches.forEach {
            if (it == irCatches.last()) {
                selectStatement(it.result)
                currentBlock.inst(Br(tryExit))
            } else {
                val catchBody = newBlock()
                val callee = Function("IsInstance", Type.boolean)
                val isInstance = currentBlock.inst(Call(callee, newVariable(Type.boolean), listOf(exception)))
                val nextCatch = newBlock("check_for_${it.parameter.name.asString()}")
                currentBlock.inst(Condbr(isInstance, catchBody, nextCatch))
                currentBlock = catchBody
                selectStatement(it.result)
                currentBlock.inst(Br(tryExit))
                currentBlock = nextCatch
            }
        }
        currentBlock = prevBlock
        return header
    }

    //-------------------------------------------------------------------------//

    private fun selectThrow(irThrow: IrThrow): Operand {
        val evaluated = selectStatement(irThrow.value)
        // TODO: call ThrowException
//        currentBlock.invoke(currentFunction.newBlock(), )
        return CfgNull // TODO: replace with Nothing type
    }

    //-------------------------------------------------------------------------//

    private fun selectTry(irTry: IrTry): Operand {
        val tryExit = newBlock("try_exit")
        currentLandingBlock = selectCatches(irTry.catches, tryExit)
        val operand = selectStatement(irTry.tryResult)
        currentBlock.inst(Br(tryExit))
        currentLandingBlock = null
        currentBlock = tryExit
        return operand
    }

    //-------------------------------------------------------------------------//

    private fun CallableDescriptor.returnsUnit()
        = returnType == context.builtIns.unitType && !isSuspend

    //-------------------------------------------------------------------------//

    private fun isUnconditional(irBranch: IrBranch): Boolean =
            irBranch.condition is IrConst<*>                            // If branch condition is constant.
                    && (irBranch.condition as IrConst<*>).value as Boolean  // If condition is "true"

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement)
        = element.acceptChildren(this, null)
}