package optimization;

import java.util.*;
import dataStructures.Blocks.*;
import dataStructures.Instructions.*;
import dataStructures.Instructions.Instruction.DeleteMode;
import dataStructures.Operator.OperatorCode;
import dataStructures.Results.*;
import intermediateCodeRepresentation.ControlFlowGraph;
import utility.Constants;

public class Optimizer
{
    private static HashMap<Integer, IResult> cpMap;
    private static int[] instructionUseCount;
    private static Integer endInstrId;
    private static Optimizer optimizer;
    
    private Optimizer()
    {
        cpMap = new HashMap<Integer, IResult>();
        instructionUseCount = new int[Constants.NUMBER_OF_INSTRUCTIONS_CAP];
        endInstrId = 0;
    }

    public void reset()
    {
        cpMap.clear();
        instructionUseCount = new int[Constants.NUMBER_OF_INSTRUCTIONS_CAP];
        endInstrId = 0;
    }

    public static Optimizer getInstance()
    {
        if(optimizer == null)
        {
            optimizer = new Optimizer();
        }

        return optimizer;
    }

    public void optimize(IBlock block, Instruction instruction)
    {
        if(instruction.opcode == OperatorCode.end)
        {
            endInstrId = instruction.id;
            return;
        }

        instruction.setAkaInstruction(instruction.operandX, instruction.operandY);

        // Copy Propagation
        if(instruction.opcode == OperatorCode.move)
        {
            // Don't disturb return instruction. But condense it.
            if(instruction.operandY instanceof InstructionResult)
            {
                instructionUseCount[instruction.id] += 1;
                if(instruction.akaI.operandX instanceof InstructionResult)
                {
                    condenseOperandX(instruction);
                }
                return;
            }

            if(instruction.operandY instanceof VariableResult)
            {
                // Don't disturb initialization of formal parameters. But condense them.
                if(instruction.akaI.operandY instanceof VariableResult 
                            && ((VariableResult)instruction.akaI.operandY).variable.version == -1)
                {
                    if(instruction.akaI.operandX instanceof VariableResult 
                                && ((VariableResult)instruction.akaI.operandY).variable.version == -1)
                    {
                        instruction.deleteMode = DeleteMode.CP;
                    }

                    instructionUseCount[instruction.id] += 1;
                    if(instruction.akaI.operandX instanceof InstructionResult)
                    {
                        condenseOperandX(instruction);
                    }
                    return;
                }

                if(instruction.operandX instanceof ConstantResult)
                {
                    instruction.deleteMode = DeleteMode.NUMBER;
                    cpMap.put(instruction.id, instruction.akaI.operandX);
                }
                else
                {
                    instruction.deleteMode = DeleteMode.CP;
                    if(instruction.akaI.operandX instanceof InstructionResult)
                    {
                        condenseOperandX(instruction);
                        if(instruction.akaI.operandX instanceof InstructionResult)
                        {
                            instructionUseCount[instruction.akaI.operandX.getIid()] -= 1;
                        }
                    }
                    cpMap.put(instruction.akaI.operandY.getIid(), instruction.akaI.operandX);
                }
            }
        }
        // Common subexpression elimination
        else if(instruction.opcode == OperatorCode.neg || instruction.opcode == OperatorCode.add ||
                instruction.opcode == OperatorCode.mul || instruction.opcode == OperatorCode.sub ||
                instruction.opcode == OperatorCode.div || instruction.opcode == OperatorCode.cmp ||
                instruction.opcode == OperatorCode.adda || instruction.opcode == OperatorCode.load ||
                instruction.opcode == OperatorCode.store || instruction.opcode == OperatorCode.phi)
        {
            if(instruction.akaI.operandX != null 
                    && instruction.akaI.operandX instanceof InstructionResult)
            {
                condenseOperandX(instruction);
            }
    
            if(instruction.akaI.operandY != null 
                    && instruction.akaI.operandY instanceof InstructionResult)
            {
                condenseOperandY(instruction);
            }

            Instruction cSubexpression;
            if(instruction.opcode == OperatorCode.load)
            {
                Instruction fabLoad = instruction.akaI.clone();
                Instruction addaI = block.getInstruction(instruction.operandY.getIid());
                Instruction addI = block.getInstruction(addaI.operandX.getIid());
                fabLoad.operandX = addI.operandY;
                cSubexpression = block.searchCommonSubexpression(fabLoad);
            }
            else
            {
                cSubexpression = block.searchCommonSubexpression(instruction.akaI);
            }
    
            if(cSubexpression != null)
            {
                instruction.setAkaInstruction(cSubexpression);
                instructionUseCount[cSubexpression.id] += 1;
                cpMap.put(instruction.id, new InstructionResult(cSubexpression.id));
                instruction.deleteMode = DeleteMode.CSE;
            }
            else
            {
                // Phis can also be condensed.
                if(instruction.opcode == OperatorCode.phi)
                {
                    if(instruction.akaI.operandX instanceof InstructionResult 
                            && instruction.akaI.operandY instanceof InstructionResult
                                && instruction.akaI.operandX.getIid() == instruction.akaI.operandY.getIid())
                    {
                        instruction.deleteMode = DeleteMode.CP;
                        cpMap.put(instruction.id, instruction.operandX);
                    }
                }
                // Adda issue.
                /*
                else if(instruction.opcode == OperatorCode.store)
                {
                    Integer addaId = instruction.operandX.getIid();
                    if(cpMap.containsKey(addaId))
                    {
                        Instruction addaInstruction = block.getInstruction(addaId);
                        addaInstruction.deleteMode = DeleteMode._NotDeleted;
                        addaInstruction.akaI.id = addaInstruction.id;
                        instruction.akaI.operandX = instruction.operandX;
                    }
                }
                else if(instruction.opcode == OperatorCode.load)
                {
                    Integer addaId = instruction.operandY.getIid();
                    if(cpMap.containsKey(addaId))
                    {
                        Instruction addaInstruction = block.getInstruction(addaId);
                        addaInstruction.deleteMode = DeleteMode._NotDeleted;
                        addaInstruction.akaI.id = addaInstruction.id;
                        instruction.akaI.operandY = instruction.operandY;
                    }
                }
                */

                if(instruction.opcode == OperatorCode.store)
                {
                    Instruction fabStore = new Instruction(0);
                    Instruction addaI = block.getInstruction(instruction.operandY.getIid());
                    Instruction addI = block.getInstruction(addaI.operandX.getIid());
                    fabStore.setExternal(0, OperatorCode.store, addI.operandY, null);
                    block.addSubexpression(fabStore);
                }
                else
                {
                    block.addSubexpression(instruction.akaI);
                }
            }
        }
        // Write and branch instructions
        else
        {
            instructionUseCount[instruction.id] += 1;
            if(instruction.akaI.operandX != null 
                    && instruction.akaI.operandX instanceof InstructionResult)
            {
                condenseOperandX(instruction);
            }
        }
    }

    private void condenseOperandX(Instruction instruction)
    {
        instructionUseCount[instruction.akaI.operandX.getIid()] += 1;
        if(cpMap.containsKey(instruction.akaI.operandX.getIid()))
        {
            instructionUseCount[instruction.akaI.operandX.getIid()] -= 1;
            instruction.akaI.operandX = cpMap.get(instruction.akaI.operandX.getIid());
            if(instruction.akaI.operandX instanceof InstructionResult)
            {
                instructionUseCount[instruction.akaI.operandX.getIid()] += 1;
            }
        }
    }

    private void condenseOperandY(Instruction instruction)
    {
        instructionUseCount[instruction.akaI.operandY.getIid()] += 1;
        if(cpMap.containsKey(instruction.akaI.operandY.getIid()))
        {
            instructionUseCount[instruction.akaI.operandY.getIid()] -= 1;
            instruction.akaI.operandY = cpMap.get(instruction.akaI.operandY.getIid());
            if(instruction.akaI.operandY instanceof InstructionResult)
            {
                instructionUseCount[instruction.akaI.operandY.getIid()] += 1;
            }
        }
    }

    public void eliminateDeadCode(ControlFlowGraph cfg)
    {
        for(int idx = 0; idx < endInstrId; idx++)
        {
            if(instructionUseCount[idx] == 0)
            {
                Instruction instruction = cfg.getInstruction(idx);
                if(instruction != null && instruction.deleteMode == DeleteMode._NotDeleted)
                {
                    instruction.deleteMode = DeleteMode.DCE;
                }
            }
        }
    }
}