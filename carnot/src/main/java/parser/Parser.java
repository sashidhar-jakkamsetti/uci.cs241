package parser;

import dataStructures.*;
import dataStructures.Results.*;
import dataStructures.Token.TokenType;
import exceptions.*;
import intermediateCodeRepresentation.*;

public class Parser 
{
    private static Parser parser;
    private Scanner scanner;
    private Token inputSym;

    private static ControlFlowGraph cfg;
    private static VariableManager vManager;
    private static IntermediateCodeGenerator iCodeGenerator;

    public static Parser getInstance(String fileName)
    {
        if(parser == null)
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
        cfg = ControlFlowGraph.getInstance();
        iCodeGenerator = IntermediateCodeGenerator.getInstance();
        vManager = VariableManager.getInstance();
        next();
        computation();
        return cfg;
    }

    private IResult designator()
    {
        VariableResult vResult = null;
        if(inputSym.isSameType(TokenType.ident))
        {
            if(vManager.isVariable(scanner.identifier2Address.get(inputSym.value)))
            {
                Variable v = new Variable(inputSym.value, scanner.identifier2Address.get(inputSym.value));
                vResult = new VariableResult();
                vResult.set(v);
            }
            else 
            {
                error(new IllegalVariableException("Undeclared variable found while parsing designator"));
                return vResult;
            }

            next();
            // Array check
            while(inputSym.isSameType(TokenType.openbracketToken))
            {
                // Get dimension info
            }
        }
        else
        {
            error(new IncorrectSyntaxException("Identifier not found while parsing designator"));
        }

        return vResult;
    }

    private IResult factor()
    {
        IResult result = null;
        switch(inputSym.type)
        {
            case ident:
                result = designator();
                if(result != null)
                {
                    Variable variable = ((VariableResult)result).variable;
                    Integer designatorVersion = vManager.getSsaVersion(variable.address);
                    variable.version = designatorVersion;
                    vManager.updateDefUseChain(variable.address, designatorVersion, iCodeGenerator.getPC());
                }
                break;
            
            case number:
                result = new ConstantResult();
                if(result != null)
                {
                    result.set(inputSym.value);
                    next();
                }
                break;
            
            case openparenToken:
                next();
                result = expression();
                if(result != null)
                {
                    if(inputSym.isSameType(TokenType.closeparenToken))
                    {
                        next();
                    }
                    else 
                    {
                        error(new IncorrectSyntaxException("Closing parenthesis not found while parsing factor"));
                    }
                }
                break;

            case callToken:
                result = funcCall();
                break;
        }

        return result;
    }

    private IResult term()
    {
        IResult xResult = factor();
        if(xResult != null)
        {
            while(inputSym.isTermOp())
            {
                Token opToken = inputSym;
                next();
    
                IResult yResult = factor();
                if(yResult != null)
                {
                    cfg.current.addInstruction(iCodeGenerator.Compute(opToken, xResult, yResult));
                }
            }
        }

        return xResult;
    }

    private IResult expression()
    {
        IResult xResult = term();
        if(xResult != null)
        {
            while(inputSym.isExpressionOp()) 
            {
                Token opToken = inputSym;
                next();
    
                IResult yResult = term();
                if(yResult != null)
                {
                    cfg.current.addInstruction(iCodeGenerator.Compute(opToken, xResult, yResult));
                }
            }    
        }

        return xResult;
    }

    private IResult relation()
    {
        IResult xResult = expression();
        if(xResult != null)
        {
            while(inputSym.isRelationOp())
            {
                Token opToken = inputSym;
                next();
    
                IResult yResult = expression();
                if(yResult != null)
                {
                    cfg.current.addInstruction(iCodeGenerator.Compute(opToken, xResult, yResult));
                }
            }
        }

        return xResult;
    }

    private void assignment()
    {
        if(inputSym.isSameType(TokenType.letToken))
        {
            IResult lhsResult = designator();
            if(lhsResult != null)
            {
                if(inputSym.isSameType(TokenType.becomesToken))
                {
                    Token opToken = inputSym;
                    next();

                    IResult rhsResult = expression();
                    if(rhsResult != null)
                    {
                        Variable variable = ((VariableResult)lhsResult).variable;
                        variable.version = iCodeGenerator.getPC();

                        vManager.updateSsaMap(variable.address, variable.version);
                        vManager.updateDefUseChain(variable.address, variable.version, variable.version);
                        cfg.current.addInstruction(iCodeGenerator.Compute(opToken, lhsResult, rhsResult));
                    }
                }
            }
        }
    }

    private IResult funcCall()
    {
        IResult fResult = null;

        return fResult;
    }

    private void computation()
    {

    }

    public void error(Exception exception)
    {
        scanner.error(exception);
    }
}
