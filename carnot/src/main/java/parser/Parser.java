package parser;

import java.util.*;

import dataStructures.*;
import dataStructures.Blocks.*;
import dataStructures.Operator.OperatorCode;
import dataStructures.Results.*;
import dataStructures.Token.TokenType;
import exceptions.*;
import intermediateCodeRepresentation.*;

public class Parser 
{
    private static Parser parser;
    private Scanner scanner;
    private Token inputSym;

    private ControlFlowGraph cfg;
    private VariableManager vManager;
    private IntermediateCodeGenerator iCodeGenerator;
    private static String cfileName;

    public static Parser getInstance(String fileName)
    {
        if(parser == null || cfileName != fileName)
        {
            parser = new Parser(fileName);

            if(parser.scanner == null)
            {
                return null;
            }
        }

        return parser;
    }

    private Parser(String fileName)
    {
        scanner = Scanner.getInstance(fileName);
    }

    private void next()
    {
        if(scanner != null)
        {
            inputSym = scanner.getSym();
        }
    }

    public ControlFlowGraph parse()
    {
        cfg = new ControlFlowGraph();
        iCodeGenerator = new IntermediateCodeGenerator();
        vManager = new VariableManager();
        next();
        cfg.done = computation();
        return cfg;
    }

    private VariableResult designator(IBlock cBlock, Function function)
    {
        VariableResult vResult = new VariableResult();
        if(inputSym.isSameType(TokenType.ident))
        {
            Integer variable = scanner.identifier2Address.get(inputSym.value);
            Variable v = new Variable(inputSym.value, scanner.identifier2Address.get(inputSym.value));

            if(vManager.isVariable(variable) || (function != null && function.vManager.isVariable(variable)))
            {
                next();
                if(vManager.isArray(variable) || (function != null && function.vManager.isArray(variable)))
                {
                    if(inputSym.isSameType(TokenType.openbracketToken))
                    {
                        ArrayList<IResult> indexList = new ArrayList<IResult>();
                        do
                        {
                            if(inputSym.isSameType(TokenType.openbracketToken))
                            {
                                next();
                                indexList.add(expression(cBlock, function));
                                if(inputSym.isSameType(TokenType.closebracketToken))
                                {
                                    next();
                                }
                                else
                                {
                                    error(new IncorrectSyntaxException("Close bracket not found in array declaration."));
                                    return null;
                                }
                            }
                            else
                            {
                                error(new IncorrectSyntaxException("Open bracket not found in array declaration."));
                                return null;
                            }
                        }while(inputSym.isSameType(TokenType.openbracketToken));
        
                        ArrayVar arrayV = new ArrayVar(v.name, v.address, v.version);
                        arrayV.indexList = indexList;
                        vResult.set(arrayV);
                    }
                    else 
                    {
                        error(new IllegalVariableException("Undeclared array found while parsing designator."));
                        return null;
                    }
                }
                else
                {
                    vResult.set(v);
                }
            }
            else 
            {
                error(new IllegalVariableException("Undeclared variable found while parsing designator."));
                return null;
            }
        }
        else
        {
            error(new IncorrectSyntaxException("Identifier not found while parsing designator."));
            return null;
        }

        // return (VariableResult)vResult.clone();
        return vResult;
    }

    private IResult factor(IBlock cBlock, Function function)
    {
        IResult result = null;
        switch(inputSym.type)
        {
            case ident:
                result = designator(cBlock, function);
                if(result != null)
                {
                    Variable v = ((VariableResult)result).variable;
                    if(vManager.isVariable(v.address))
                    {
                        Integer designatorVersion = vManager.getSsaVersion(v.address);
                        v.version = designatorVersion;
                        vManager.updateDefUseChain(v.address, designatorVersion, iCodeGenerator.getPC());

                        if(((VariableResult)result).isArray)
                        {
                            cBlock.addInstruction(iCodeGenerator.loadArrayElement(vManager, result));
                            result = result.toInstruction();
                            result.set(iCodeGenerator.getPC() - 1);
                        }
                    }
                    else if(function != null && function.vManager.isVariable(v.address))
                    {
                        Integer designatorVersion = function.vManager.getSsaVersion(v.address);
                        v.version = designatorVersion;
                        function.vManager.updateDefUseChain(v.address, designatorVersion, iCodeGenerator.getPC());

                        if(((VariableResult)result).isArray)
                        {
                            cBlock.addInstruction(iCodeGenerator.loadArrayElement(function.vManager, result));
                            result = result.toInstruction();
                            result.set(iCodeGenerator.getPC() - 1);
                        }
                    }
                }
                break;
            
            case number:
                result = new ConstantResult();
                result.set(inputSym.value);
                next();
                break;
            
            case openparenToken:
                next();
                result = expression(cBlock, function);
                if(result != null)
                {
                    if(inputSym.isSameType(TokenType.closeparenToken))
                    {
                        result = result.toInstruction();
                        next();
                    }
                    else 
                    {
                        error(new IncorrectSyntaxException("Closing parenthesis not found while parsing factor."));
                    }
                }
                break;

            case callToken:
                result = funcCall(cBlock, function);
                break;
        }

        if(result != null)
        {
            return result.clone();
        }
        return result;
    }

    private IResult term(IBlock cBlock, Function function)
    {
        IResult xResult = factor(cBlock, function);
        if(xResult != null)
        {
            while(inputSym.isTermOp())
            {
                Token opToken = inputSym;
                next();
    
                IResult yResult = factor(cBlock, function);
                if(yResult != null)
                {
                    if(xResult.getIid() > 0) 
                    {
                        xResult = xResult.toInstruction();
                    }
                    cBlock.addInstruction(iCodeGenerator.compute(opToken, xResult, yResult));
                    xResult = xResult.clone();
                    xResult.setIid(iCodeGenerator.getPC() - 1);
                }
            }
        }

        return xResult;
    }

    private IResult expression(IBlock cBlock, Function function)
    {
        IResult xResult = term(cBlock, function);
        if(xResult != null)
        {
            while(inputSym.isExpressionOp()) 
            {
                Token opToken = inputSym;
                next();
    
                IResult yResult = term(cBlock, function);
                if(yResult != null)
                {
                    if(xResult.getIid() > 0) 
                    {
                        xResult = xResult.toInstruction();
                    }
                    cBlock.addInstruction(iCodeGenerator.compute(opToken, xResult, yResult));
                    xResult = xResult.clone();
                    xResult.setIid(iCodeGenerator.getPC() - 1);
                }
            }    
        }

        return xResult;
    }

    private BranchResult relation(IBlock cBlock, Function function)
    {
        BranchResult bResult = new BranchResult();
        IResult xResult = expression(cBlock, function);
        if(xResult != null)
        {
            if(xResult.getIid() > 0) 
            {
                xResult = xResult.toInstruction();
                xResult.setIid(iCodeGenerator.getPC() - 1);
            }
            while(inputSym.isRelationOp())
            {
                Token opToken = inputSym;
                next();
    
                IResult yResult = expression(cBlock, function);
                if(yResult != null)
                {
                    if(yResult.getIid() > 0) 
                    {
                        yResult = yResult.toInstruction();
                        yResult.setIid(iCodeGenerator.getPC() - 1);
                    }
                    cBlock.addInstruction(iCodeGenerator.compute(opToken, xResult, yResult));
                    bResult.condition = opToken;
                    bResult.fixuplocation = iCodeGenerator.getPC();
                    bResult.iid = iCodeGenerator.getPC() - 1;
                    bResult.targetBlock = cBlock;
                }
            }
        }

        return (BranchResult)bResult.clone();
    }

    private void assignment(IBlock cBlock, Function function)
    {
        if(inputSym.isSameType(TokenType.letToken))
        {
            next();
            IResult lhsResult = designator(cBlock, function);
            if(lhsResult != null)
            {
                if(inputSym.isSameType(TokenType.becomesToken))
                {
                    Token opToken = inputSym;
                    next();

                    IResult rhsResult = expression(cBlock, function);
                    if(rhsResult != null)
                    {
                        if(rhsResult.getIid() > 0) 
                        {
                            rhsResult = rhsResult.toInstruction();
                            rhsResult.set(iCodeGenerator.getPC() - 1);
                        }

                        Variable v = ((VariableResult)lhsResult).variable;
                        if(vManager.isVariable(v.address))
                        {
                            if(((VariableResult)lhsResult).isArray)
                            {
                                cBlock.addInstruction(iCodeGenerator.storeArrayElement(vManager, lhsResult, rhsResult));
                                lhsResult = lhsResult.toInstruction();
                                lhsResult.set(iCodeGenerator.getPC() - 1);
                            }
                            else
                            {
                                cBlock.addInstruction(iCodeGenerator.compute(opToken, lhsResult, rhsResult));
                            }

                            v.version = iCodeGenerator.getPC() - 1;
                            vManager.updateSsaMap(v.address, v.version);
                            vManager.updateDefUseChain(v.address, v.version, v.version);
                        }
                        else if(function != null && function.vManager.isVariable(v.address))
                        {
                            if(((VariableResult)lhsResult).isArray)
                            {
                                cBlock.addInstruction(iCodeGenerator.storeArrayElement(function.vManager, lhsResult, rhsResult));
                                lhsResult = lhsResult.toInstruction();
                                lhsResult.set(iCodeGenerator.getPC() - 1);
                            }
                            else
                            {
                                cBlock.addInstruction(iCodeGenerator.compute(opToken, lhsResult, rhsResult));
                            }

                            v.version = iCodeGenerator.getPC() - 1;
                            function.vManager.updateSsaMap(v.address, v.version);
                            function.vManager.updateDefUseChain(v.address, v.version, v.version);
                        }
                    }
                }
            }
        }
    }

    private IResult funcCall(IBlock cBlock, Function function)
    {
        if(inputSym.isSameType(TokenType.callToken))
        {
            Token opToken = inputSym;
            next();
            Function callFunction = new Function(inputSym.value, scanner.identifier2Address.get(inputSym.value));
            if(cfg.isExists(callFunction))
            {
                callFunction = cfg.getFunction(callFunction);
                next();
                if(inputSym.isSameType(TokenType.openparenToken))
                {
                    Integer idx = 0;
                    do
                    {
                        next();
                        IResult pResult = expression(cBlock, function);
                        if(pResult != null)
                        {
                            cBlock.addInstruction(iCodeGenerator.compute(OperatorCode.move, pResult, callFunction.getParameter(idx++)));
                        }
                    }while(inputSym.isSameType(TokenType.commaToken));
                    
                    if(inputSym.isSameType(TokenType.closeparenToken))
                    {
                        next();
                    }
                    else
                    {
                        error(new IncorrectSyntaxException("Closing parenthesis not found while parsing funcCall statement."));
                        return null;
                    }
                }

                BranchResult bResult = new BranchResult();
                bResult.set(iCodeGenerator.getPC());
                bResult.set(callFunction.head);
                bResult.condition = opToken;
                
                cBlock.addInstruction(iCodeGenerator.compute(opToken, bResult));
                if(callFunction.returnInstruction != null)
                {
                    return callFunction.returnInstruction.clone();
                }
                return callFunction.returnInstruction;
            }

            if(Operator.standardIoOperator.containsKey(inputSym.value))
            {
                opToken = inputSym;
                if(inputSym.value.equals("InputNum"))
                {
                    next();
                    if(inputSym.isSameType(TokenType.openparenToken))
                    {
                        next();
                        if(inputSym.isSameType(TokenType.closeparenToken))
                        {
                            next();
                        }
                        else
                        {
                            error(new IncorrectSyntaxException("Close parenthesis not found while parsing InputNum statement."));
                            return null;
                        }
                    }
                    else
                    {
                        error(new IncorrectSyntaxException("Open parenthesis not found while parsing InputNum statement."));
                        return null;
                    }
                    cBlock.addInstruction(iCodeGenerator.compute(opToken, null, null));
                    return new InstructionResult(iCodeGenerator.getPC() - 1);
                }
                else if(inputSym.value.equals("OutputNum"))
                {
                    next();
                    if(inputSym.isSameType(TokenType.openparenToken))
                    {
                        next();
                        IResult pResult = expression(cBlock, function);
                        if(pResult != null)
                        {
                            cBlock.addInstruction(iCodeGenerator.compute(opToken, pResult, null));
                        }
                        if(inputSym.isSameType(TokenType.closeparenToken))
                        {
                            next();
                            return null;
                        }
                        else
                        {
                            error(new IncorrectSyntaxException("Closing parenthesis not found while parsing OutputNum statement."));
                            return null;
                        }
                    }
                    else
                    {
                        error(new IncorrectSyntaxException("Open parenthesis not found while parsing OutputNum statement."));
                        return null;
                    }
                }
                else
                {
                    next();
                    if(inputSym.isSameType(TokenType.openparenToken))
                    {
                        next();
                        if(inputSym.isSameType(TokenType.closeparenToken))
                        {
                            next();
                        }
                        else
                        {
                            error(new IncorrectSyntaxException("Close parenthesis not found while parsing InputNum statement."));
                            return null;
                        }
                    }
                    else
                    {
                        error(new IncorrectSyntaxException("Open parenthesis not found while parsing InputNum statement."));
                        return null;
                    }
                    cBlock.addInstruction(iCodeGenerator.compute(opToken, null, null));
                    return null;
                }
            }
        }
        else
        {
            error(new IncorrectSyntaxException("Call token not found while parsing funcCall statement."));
            return null;
        }

        return null;
    }

    private IBlock ifStatement(IBlock cBlock, Function function)
    {
        JoinBlock jBlock = null;
        if(inputSym.isSameType(TokenType.ifToken))
        {
            next();
            IfBlock iBlock = cfg.initializeIfBlock();
            iBlock.setParent(cBlock);
            cBlock.setChild(iBlock);

            jBlock = cfg.initializeJoinBlock();
            iBlock.setJoinBlock(jBlock);
            jBlock.setParent(iBlock);

            BranchResult bResult = relation(iBlock, function);
            iBlock.addInstruction(iCodeGenerator.compute(bResult.condition, bResult));

            if(function == null)
            {
                cBlock.freezeSsa(vManager.getSsaMap(), null);
                iBlock.freezeSsa(vManager.getSsaMap(), null);
            }
            else 
            {
                cBlock.freezeSsa(vManager.getSsaMap(), function.vManager.getSsaMap());
                iBlock.freezeSsa(vManager.getSsaMap(), function.vManager.getSsaMap());
            }

            if(inputSym.isSameType(TokenType.thenToken))
            {
                BranchResult bResult2 = new BranchResult();
                bResult2.condition = inputSym;

                next();
                Block tBlock = cfg.initializeBlock();
                iBlock.setThenBlock(tBlock);
                tBlock.setParent(iBlock);

                tBlock = (Block)statSequence(tBlock, function);
                if(tBlock == null)
                {
                    return null;
                }
                bResult2.set(jBlock);
                tBlock.addInstruction(iCodeGenerator.compute(bResult2.condition, bResult2));
                tBlock.setChild(jBlock);
                jBlock.setThenBlock(tBlock);
                
                if(function == null)
                {
                    tBlock.freezeSsa(vManager.getSsaMap(), null);
                }
                else 
                {
                    tBlock.freezeSsa(vManager.getSsaMap(), function.vManager.getSsaMap());
                }

                if(inputSym.isSameType(TokenType.elseToken))
                {
                    next();
                    Block eBlock = cfg.initializeBlock();
                    iBlock.setElseBlock(eBlock);
                    eBlock.setParent(iBlock);
                    iBlock.fixupBranch(bResult.fixuplocation, eBlock);

                    vManager.setSsaMap(cBlock.getGlobalSsa());
                    if(function != null)
                    {
                        function.vManager.setSsaMap(cBlock.getLocalSsa());
                    }
                    
                    eBlock = (Block)statSequence(eBlock, function);
                    if(eBlock == null)
                    {
                        return null;
                    }
                    eBlock.setChild(jBlock);
                    jBlock.setElseBlock(eBlock);
                    
                    if(function == null)
                    {
                        eBlock.freezeSsa(vManager.getSsaMap(), null);
                    }
                    else 
                    {
                        eBlock.freezeSsa(vManager.getSsaMap(), function.vManager.getSsaMap());
                    }
                }
                else
                {
                    iBlock.fixupBranch(bResult.fixuplocation, jBlock);
                }

                if(inputSym.isSameType(TokenType.fiToken))
                {
                    next();
                    jBlock.createPhis(scanner.address2Identifier, iCodeGenerator);

                    if(function == null)
                    {
                        jBlock.updateIncomingVManager(vManager, null);

                    }
                    else
                    {
                        jBlock.updateIncomingVManager(vManager, function.vManager);
                    }
                }
                else
                {
                    error(new IncorrectSyntaxException("Fi token not found while parsing if statement."));
                    return null;
                }
            }
            else
            {
                error(new IncorrectSyntaxException("Then token not found while parsing if statement."));
                return null;
            }
        }
        else 
        {
            error(new IncorrectSyntaxException("If token not found while parsing if statement."));
            return null;
        }

        return jBlock;
    }

    private IBlock whileStatement(IBlock cBlock, Function function)
    {
        IBlock fBlock = null;
        if(inputSym.isSameType(TokenType.whileToken))
        {
            next();
            WhileBlock wBlock = cfg.initializeWhileBlock();
            wBlock.setParent(cBlock);
            cBlock.setChild(wBlock);

            IBlock lBlock = cfg.initializeBlock();
            wBlock.setLoopBlock(lBlock);
            lBlock.setParent(wBlock);

            BranchResult bResult = relation(wBlock, function);
            wBlock.addInstruction(iCodeGenerator.compute(bResult.condition, bResult));
            
            if(function == null)
            {
                cBlock.freezeSsa(vManager.getSsaMap(), null);
                wBlock.freezeSsa(vManager.getSsaMap(), null);
            }
            else 
            {
                cBlock.freezeSsa(vManager.getSsaMap(), function.vManager.getSsaMap());
                wBlock.freezeSsa(vManager.getSsaMap(), function.vManager.getSsaMap());
            }

            if(inputSym.isSameType(TokenType.doToken))
            {
                BranchResult bResult2 = new BranchResult();
                bResult2.condition = inputSym;
                bResult2.set(wBlock);
                next();

                lBlock = statSequence(lBlock, function);
                if(lBlock == null)
                {
                    return null;
                }
                if(inputSym.isSameType(TokenType.odToken))
                {
                    next();
                    bResult2.set(wBlock);
                    lBlock.addInstruction(iCodeGenerator.compute(bResult2.condition, bResult2));
                    lBlock.setChild(wBlock);
            
                    if(function == null)
                    {
                        lBlock.freezeSsa(vManager.getSsaMap(), null);
                    }
                    else 
                    {
                        lBlock.freezeSsa(vManager.getSsaMap(), function.vManager.getSsaMap());
                    }

                    wBlock.createPhis(lBlock, scanner.address2Identifier, iCodeGenerator);
                    if(function == null)
                    {
                        wBlock.updateIncomingVManager(vManager, null);
                    }
                    else
                    {
                        wBlock.updateIncomingVManager(vManager, function.vManager);
                    }
                    wBlock.updatePhiVarOccurances();

                    fBlock = cfg.initializeBlock();
                    fBlock.setParent(wBlock);
                    wBlock.setFollowBlock(fBlock);
                    wBlock.fixupBranch(bResult.fixuplocation, fBlock);
                }
                else
                {
                    error(new IncorrectSyntaxException("Od token not found while parsing while statement."));
                    return null;
                }
            }
            else
            {
                error(new IncorrectSyntaxException("Do token not found while parsing while statement."));
                return null;
            }
        }
        else
        {
            error(new IncorrectSyntaxException("While token not found while parsing while statement."));
            return null;
        }

        return fBlock;
    }

    private IResult returnStatement(IBlock cBlock, Function function)
    {
        IResult rResult = null;
        if(inputSym.isSameType(TokenType.returnToken))
        {
            Token opToken = inputSym;
            next();
            rResult = expression(cBlock, function);
            if(rResult != null)
            {
                InstructionResult iResult = new InstructionResult(iCodeGenerator.getPC());
                if(function.returnInstruction == null || function.returnInstruction.iid == -1)
                {
                    function.returnInstruction = iResult;
                }
                else
                {
                    iResult.set(function.returnInstruction.iid);
                }
                cBlock.addInstruction(iCodeGenerator.compute(opToken, iResult, rResult));
                rResult = rResult.clone();
            }
        }
        else
        {
            error(new IncorrectSyntaxException("Return token not found while parsing return statement."));
            return null;
        }

        return rResult;
    }

    private IBlock statement(IBlock cBlock, Function function)
    {
        IBlock block = null;
        if(inputSym.isSameType(TokenType.letToken))
        {
            assignment(cBlock, function);
            block = cBlock;
        }
        else if(inputSym.isSameType(TokenType.callToken))
        {
            funcCall(cBlock, function);
            block = cBlock;
        }
        else if(inputSym.isSameType(TokenType.ifToken))
        {
            block = ifStatement(cBlock, function);
        }
        else if(inputSym.isSameType(TokenType.whileToken))
        {
            block = whileStatement(cBlock, function);
        }
        else if(inputSym.isSameType(TokenType.returnToken))
        {
            returnStatement(cBlock, function);
            block = cBlock;
        }
        else
        {
            error(new IncorrectSyntaxException("No valid token found while parsing statement."));
        }

        return block;
    }

    private IBlock statSequence(IBlock cBlock, Function function)
    {
        IBlock block = cBlock;
        do
        {
            block = statement(block, function);
            if(block == null)
            {
                return null;
            }

            if(inputSym.isSameType(TokenType.semiToken))
            {
                next();
            }
            else
            {
                break;
            }
        }while(true);
        
        return block;
    }

    private ArrayList<Integer> typeDecl()
    {
        ArrayList<Integer> dimensionList = new ArrayList<Integer>();
        if(inputSym.isSameType(TokenType.varToken))
        {
            next();
        }
        else if(inputSym.isSameType(TokenType.arrToken))
        {
            next();
            do
            {
                if(inputSym.isSameType(TokenType.openbracketToken))
                {
                    next();
                    if(inputSym.isSameType(TokenType.number))
                    {
                        dimensionList.add(Integer.parseInt(inputSym.value));
                        next();
                        if(inputSym.isSameType(TokenType.closebracketToken))
                        {
                            next();
                        }
                        else
                        {
                            error(new IncorrectSyntaxException("Close bracket not found in array declaration."));
                        }
                    }
                    else
                    {
                        error(new IncorrectSyntaxException("Number not found in array declaration."));
                    }
                }
                else
                {
                    error(new IncorrectSyntaxException("Open bracket not found in array declaration."));
                }
            }while(inputSym.isSameType(TokenType.openbracketToken));
        }
        else 
        {
            error(new IncorrectSyntaxException("Variable/Array declaration not found while parsing type declaration."));
        }
        return dimensionList;
    }

    private void varDecl(Function function)
    {
        ArrayList<Integer> dimentionList = new ArrayList<Integer>();
        dimentionList = typeDecl();
        do
        {
            if(inputSym.isSameType(TokenType.ident))
            {
                VariableResult vResult = new VariableResult();
                if(dimentionList.isEmpty())
                {
                    Variable var = new Variable(inputSym.value, scanner.identifier2Address.get(inputSym.value), iCodeGenerator.getPC());
                    vResult.set(var);
                }
                else
                {
                    ArrayVar var = new ArrayVar(inputSym.value, scanner.identifier2Address.get(inputSym.value), 
                                                        iCodeGenerator.getPC(), dimentionList);
                    vResult.set(var);
                }
                                
                try 
                {
                    if(function == null)
                    {
                        iCodeGenerator.declareVariable(cfg.head, vManager, vResult);
                    }
                    else
                    {
                        iCodeGenerator.declareVariable(function.head, function.vManager, vResult);
                    }
                }
                catch(Exception e)
                {
                    error(e);
                }
            }
            else
            {
                error(new IncorrectSyntaxException("No Identifier found in Variable Declaration."));
                return;
            }

            next();
            if(inputSym.isSameType(TokenType.commaToken))
            {
                next();
            }
            
        }while(inputSym.isSameType(TokenType.ident));

        if(inputSym.isSameType(TokenType.semiToken))
        {
            next();
        }
        else
        {
            error(new IncorrectSyntaxException("Semi comma not found in Variable Declaration."));
        }
    }

    private void formalParam(Function function)
    {
        if(inputSym.isSameType(TokenType.openparenToken))
        {
            next();
            while(inputSym.isSameType(TokenType.ident))
            {
                Variable v = new Variable(inputSym.value, scanner.identifier2Address.get(inputSym.value), iCodeGenerator.getPC());
                VariableResult vResult = new VariableResult();
                vResult.set(v);
                try
                {
                    iCodeGenerator.declareVariable(function.head, function.vManager, vResult);
                }
                catch(Exception e)
                {
                    error(e);
                }
                
                function.addParameter(vResult);
                next();

                if(inputSym.isSameType(TokenType.commaToken))
                {
                    next();
                    if(!inputSym.isSameType(TokenType.ident))
                    {
                        error(new IncorrectSyntaxException("Identifier not found while parsing formal paramters declaration."));
                        return;
                    }
                }
                else
                {
                    break;
                }
            }

            if(inputSym.isSameType(TokenType.closeparenToken))
            {
                next();
            }
            else
            {
                error(new IncorrectSyntaxException("Close parenthesis not found while parsing formal parameters declaration."));
                return;
            }
        }
        else
        {
            error(new IncorrectSyntaxException("Open parenthesis not found while parsing formal parameters declaration."));
        }
    }

    private void funcDecl()
    {
        while(inputSym.isSameType(TokenType.funcToken) || inputSym.isSameType(TokenType.procToken))
        {
            next();
            if(inputSym.isSameType(TokenType.ident))
            {
                Function function = new Function(inputSym.value, scanner.identifier2Address.get(inputSym.value));
                if(cfg.isExists(function))
                {
                    error(new IncorrectSyntaxException("Function already exists."));
                    return;
                }
                function.head = (Block)cfg.initializeBlock();
                cfg.addFunction(function);

                next();
                if(inputSym.isSameType(TokenType.openparenToken))
                {
                    formalParam(function);
                }

                if(inputSym.isSameType(TokenType.semiToken))
                {
                    next();
                    funcBody(function);

                    if(inputSym.isSameType(TokenType.semiToken))
                    {
                        next();
                    }
                    else 
                    {
                        error(new IncorrectSyntaxException("Semi comma not found while parsing function declaration."));
                        return;
                    }
                }
                else
                {
                    error(new IncorrectSyntaxException("Semi comma not found while parsing function declaration."));
                    return;
                }
            }
        }
    }

    private void funcBody(Function function)
    {
        while(inputSym.isSameType(TokenType.varToken) || inputSym.isSameType(TokenType.arrToken))
        {
            varDecl(function);
        }

        if(inputSym.isSameType(TokenType.beginToken))
        {
            next();
            statSequence(function.head, function);
            if(inputSym.isSameType(TokenType.endToken))
            {
                next();
            }
            else 
            {
                error(new IncorrectSyntaxException("End token not found while parsing function body."));
                return;
            }
        }
        else
        {
            error(new IncorrectSyntaxException("Begin token not found while parsing function body."));
            return;
        }
    }

    private boolean computation()
    {
        if(inputSym.isSameType(TokenType.mainToken))
        {
            next();

            while(inputSym.isSameType(TokenType.varToken) || inputSym.isSameType(TokenType.arrToken))
            {
                varDecl(null);
            }
            while(inputSym.isSameType(TokenType.funcToken) || inputSym.isSameType(TokenType.procToken))
            {
                funcDecl();
            }

            if(inputSym.isSameType(TokenType.beginToken))
            {
                next();
                IBlock lBlock = statSequence(cfg.head, null);
                if(lBlock == null)
                {
                    return false;
                }
                if(inputSym.isSameType(TokenType.endToken))
                {
                    next();
                    if(inputSym.isSameType(TokenType.periodToken))
                    {
                        Token opToken = inputSym;
                        next();
                        lBlock.addInstruction(iCodeGenerator.compute(opToken, null, null));
                        return true;
                    }
                    else
                    {
                        error(new IncorrectSyntaxException("Period token not found while parsing main function body."));
                    }
                }
                else 
                {
                    error(new IncorrectSyntaxException("End token not found while parsing main function body."));
                }
            }
            else
            {
                error(new IncorrectSyntaxException("Begin token not found while parsing main function body."));
            }
        }
        else
        {
            error(new IncorrectSyntaxException("Main token not found while parsing the program."));
        }

        return false;
    }

    public void error(Exception exception)
    {
        scanner.error(exception);
    }
}
