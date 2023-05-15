/*
 *  Copyright 2021 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.restructurization;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntStack;
import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.teavm.common.DominatorTree;
import org.teavm.common.Graph;
import org.teavm.common.GraphUtils;
import org.teavm.model.BasicBlock;
import org.teavm.model.Instruction;
import org.teavm.model.Program;
import org.teavm.model.instructions.AbstractInstructionVisitor;
import org.teavm.model.instructions.BinaryBranchingInstruction;
import org.teavm.model.instructions.BranchingInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.InstructionVisitor;
import org.teavm.model.instructions.JumpInstruction;
import org.teavm.model.instructions.SwitchInstruction;
import org.teavm.model.util.ProgramUtils;

public class Restructurization {
    private Program program;
    private Graph cfg;
    private DominatorTree dom;
    private Graph domGraph;
    private int[] dfs;
    private Block currentStatement;
    private BasicBlock currentBlock;
    private LabeledBlock[] jumpTargets;
    private BasicBlock nextBlock;
    private LoopBlock[] loopExits;
    private LoopBlock[] loops;
    private ObjectIntMap<LabeledBlock> labeledBlockUsages = new ObjectIntHashMap<>();
    private boolean[] processingTryCatch;
    private boolean[] currentSequenceNodes;
    private RewindVisitor rewindVisitor = new RewindVisitor();

    public Block apply(Program program) {
        this.program = program;
        prepare();
        currentBlock = program.basicBlockAt(0);
        nextBlock = null;
        calculateResult();
        var result = rewindVisitor.rewind(currentStatement);
        cleanup();
        return result;
    }

    private static int blockEnterNode(BasicBlock block) {
        return blockEnterNode(block.getIndex());
    }

    private static int blockEnterNode(int blockIndex) {
        return blockIndex * 2;
    }

    private static int blockExitNode(BasicBlock block) {
        return blockExitNode(block.getIndex());
    }

    private static int blockExitNode(int blockIndex) {
        return blockIndex * 2 + 1;
    }

    private static int owningBlockIndex(int node) {
        return node / 2;
    }

    private BasicBlock owningBlock(int node) {
        return program.basicBlockAt(owningBlockIndex(node));
    }

    private int enteringBlockCount(BasicBlock block) {
        return cfg.incomingEdgesCount(block.getIndex() * 2);
    }

    private void prepare() {
        cfg = ProgramUtils.buildControlFlowGraph2(program);
        dfs = GraphUtils.dfs(cfg);
        dom = GraphUtils.buildDominatorTree(cfg);
        domGraph = GraphUtils.buildDominatorGraph(dom, cfg.size());
        jumpTargets = new LabeledBlock[program.basicBlockCount()];
        processingTryCatch = new boolean[program.basicBlockCount()];
        currentSequenceNodes = new boolean[program.basicBlockCount()];
        loopExits = new LoopBlock[program.basicBlockCount()];
        loops = new LoopBlock[program.basicBlockCount()];
    }

    private void cleanup() {
        program = null;
        dom = null;
        cfg = null;
        dfs = null;
        currentStatement = null;
        jumpTargets = null;
        currentSequenceNodes = null;
        loopExits = null;
        loops = null;
    }

    private void calculateResult() {
        while (currentBlock != null) {
            if (loopExits[currentBlock.getIndex()] != null) {
                var jump = new BreakBlock();
                jump.target = loopExits[currentBlock.getIndex()];
                append(jump);
                break;
            } else if (loops[currentBlock.getIndex()] == null && isLoopHead()) {
                processLoop();
            } else if (!processTryCatchHeader()) {
                var simpleBlock = new SimpleBlock();
                simpleBlock.basicBlock = currentBlock;
                for (int i = 0; i < currentBlock.getTryCatchBlocks().size(); ++i) {
                    var tryCatch = currentBlock.getTryCatchBlocks().get(i);
                    simpleBlock.tryCatch = new TryCatchNode(
                            tryCatch.getExceptionType(),
                            tryCatch.getHandler().getExceptionVariable(),
                            jumpTargets[tryCatch.getHandler().getIndex()],
                            simpleBlock,
                            simpleBlock.tryCatch
                    );
                }
                append(simpleBlock);
                currentBlock.getLastInstruction().acceptVisitor(instructionDecompiler);
            }
        }
    }

    private boolean processTryCatchHeader() {
        if (processingTryCatch[currentBlock.getIndex()]) {
            return false;
        }
        var immediatelyDominatedNodes = domGraph.outgoingEdges(blockEnterNode(currentBlock));
        if (immediatelyDominatedNodes.length == 1) {
            return false;
        }

        var childBlockCount = immediatelyDominatedNodes.length;
        var childBlocks = new BasicBlock[childBlockCount];
        for (int i = 0; i < childBlocks.length; i++) {
            childBlocks[i] = owningBlock(immediatelyDominatedNodes[i]);
        }

        Arrays.sort(childBlocks, Comparator.comparing(b -> dfs[blockEnterNode(b)]));

        var blockStatements = new SimpleLabeledBlock[childBlockCount - 1];
        for (int i = 1; i < childBlocks.length; i++) {
            var childBlock = childBlocks[i];
            var blockStatement = new SimpleLabeledBlock();
            jumpTargets[childBlock.getIndex()] = blockStatement;
            blockStatements[i - 1] = blockStatement;
        }

        processingTryCatch[currentBlock.getIndex()] = true;
        processTryBlockStatements(blockStatements, childBlocks);
        processingTryCatch[currentBlock.getIndex()] = false;
        currentBlock = childBlocks.length > 0 ? childBlocks[childBlocks.length - 1] : null;
        return true;
    }

    private void processTryBlockStatements(SimpleLabeledBlock[] blockStatements, BasicBlock[] childBlocks) {
        var result = processBlock(childBlocks[0], childBlocks[1]);
        for (int i = 1; i < childBlocks.length - 1; ++i) {
            var childBlock = childBlocks[i];
            var nextChildBlock = childBlocks[i + 1];
            var statement = processBlock(childBlock, nextChildBlock);
            firstBlockToBalance = result;
            secondBlockToBalance = statement;
            balanceTryCatch();
            result = firstBlockToBalance;
            statement = secondBlockToBalance;
            firstBlockToBalance = null;
            secondBlockToBalance = null;
            var blockStatement = blockStatements[i - 1];
            blockStatement.body = result;
            blockStatement.tryCatch = result.tryCatch;
            result = appendTryCatchOptimized(blockStatement, statement);
        }
        var finalBlockStatement = blockStatements[blockStatements.length - 1];
        finalBlockStatement.body = result;
        finalBlockStatement.body.tryCatch = result.tryCatch;
        result = finalBlockStatement;
        currentStatement = append(currentStatement, result);
    }

    private Block appendTryCatchOptimized(SimpleLabeledBlock labeledStatement, Block next) {
        if (!(labeledStatement.body instanceof TryBlock)) {
            return append(labeledStatement, next);
        }
        var tryBlock = (TryBlock) labeledStatement.body;
        if (!(tryBlock.catchBlock instanceof BreakBlock)) {
            return append(labeledStatement, next);
        }
        var br = (BreakBlock) tryBlock.catchBlock;
        if (br.target != labeledStatement) {
            return append(labeledStatement, next);
        }

        tryBlock.catchBlock = next;
        if (decreaseUsage(labeledStatement)) {
            return tryBlock;
        }

        return append(labeledStatement, next);
    }

    private void processLoop() {
        var currentStatementBackup = currentStatement;
        var nextBlockBackup = nextBlock;

        var loop = new LoopBlock();
        fillLoopNodes();
        var loopExit = getBestExit();
        if (loopExits[loopExit.getIndex()] != null) {
            loopExit = null;
        } else {
            loopExits[loopExit.getIndex()] = loop;
        }
        nextBlock = currentBlock;
        var loopHead = currentBlock;
        jumpTargets[currentBlock.getIndex()] = loop;
        loops[loopHead.getIndex()] = loop;
        currentStatement = null;
        calculateResult();
        loops[loopHead.getIndex()] = null;
        loop.body = currentStatement;
        currentBlock = loopExit;

        currentStatement = currentStatementBackup;
        append(loop);
        nextBlock = nextBlockBackup;
        if (loopExit != null) {
            loopExits[loopExit.getIndex()] = null;
        }
    }

    private BasicBlock getBestExit() {
        var stack = new IntStack();
        stack.push(currentBlock.getIndex());
        var nonLoopTargets = new IntArrayList();
        BasicBlock bestExit = null;
        int bestExitScore = 0;

        while (!stack.isEmpty()) {
            int node = stack.pop();
            var targets = domGraph.outgoingEdges(blockExitNode(node));

            for (int target : targets) {
                if (!currentSequenceNodes[owningBlockIndex(target)]) {
                    nonLoopTargets.add(owningBlockIndex(target));
                }
            }

            if (!nonLoopTargets.isEmpty()) {
                int bestNonLoopTarget = nonLoopTargets.get(0);
                for (int i = 1; i < nonLoopTargets.size(); ++i) {
                    int candidate = nonLoopTargets.get(i);
                    if (dfs[blockEnterNode(bestNonLoopTarget)] < dfs[blockEnterNode(candidate)]) {
                        bestNonLoopTarget = candidate;
                    }
                }
                nonLoopTargets.clear();
                int score = getComplexity(bestNonLoopTarget);
                if (score > bestExitScore) {
                    bestExitScore = score;
                    bestExit = program.basicBlockAt(bestNonLoopTarget);
                }
            }

            for (int target : targets) {
                if (currentSequenceNodes[owningBlockIndex(target)]) {
                    stack.push(owningBlockIndex(target));
                }
            }
        }

        return bestExit;
    }

    private int getComplexity(int blockIndex) {
        int complexity = 0;
        var stack = new IntStack();
        stack.push(blockIndex);
        var visited = new boolean[program.basicBlockCount()];
        while (!stack.isEmpty()) {
            blockIndex = stack.pop();
            if (visited[blockIndex]) {
                continue;
            }
            visited[blockIndex] = true;
            complexity += program.basicBlockAt(blockIndex).instructionCount();
            for (int successor : cfg.outgoingEdges(blockEnterNode(blockIndex))) {
                int successorIndex = owningBlockIndex(successor);
                if (!visited[successorIndex]) {
                    stack.push(successorIndex);
                }
            }
            for (int successor : cfg.outgoingEdges(blockExitNode(blockIndex))) {
                int successorIndex = owningBlockIndex(successor);
                if (!visited[successorIndex]) {
                    stack.push(successorIndex);
                }
            }
        }
        return complexity;
    }

    private void fillLoopNodes() {
        Arrays.fill(currentSequenceNodes, false);
        var stack = new int[cfg.size()];
        int stackPtr = 0;
        stack[stackPtr++] = currentBlock.getIndex();

        while (stackPtr > 0) {
            int node = stack[--stackPtr];
            if (currentSequenceNodes[node]) {
                continue;
            }
            currentSequenceNodes[node] = true;
            for (int source : cfg.incomingEdges(blockEnterNode(node))) {
                int sourceIndex = owningBlockIndex(source);
                if (!currentSequenceNodes[sourceIndex] && dom.dominates(blockEnterNode(currentBlock), source)) {
                    stack[stackPtr++] = sourceIndex;
                }
            }
        }
    }

    private boolean isLoopHead() {
        int enterNode = blockEnterNode(currentBlock);
        for (int source : cfg.incomingEdges(enterNode)) {
            if (dom.dominates(enterNode, source)) {
                return true;
            }
        }
        return false;
    }

    private Block processBlock(BasicBlock block, BasicBlock next) {
        var currentBlockBackup = currentBlock;
        var nextBlockBackup = nextBlock;
        var currentStatementBackup = currentStatement;

        currentBlock = block;
        nextBlock = next;
        currentStatement = null;
        calculateResult();
        closeTryCatchSequence(currentStatement);
        var result = currentStatement;

        currentBlock = currentBlockBackup;
        nextBlock = nextBlockBackup;
        currentStatement = currentStatementBackup;

        return result;
    }

    private void append(Block block) {
        currentStatement = appendWithTryCatch(currentStatement, block);
    }

    private Block append(Block block, Block next) {
        return block == null ? next : block.append(next.first);
    }

    private void branch(Instruction condition, BasicBlock ifTrue, BasicBlock ifFalse) {
        if (loopExits[ifTrue.getIndex()] != null) {
            loopExitBranch(condition, false, ifTrue, ifTrue);
            return;
        } else if (loopExits[ifFalse.getIndex()] != null) {
            loopExitBranch(condition, true, ifFalse, ifTrue);
            return;
        }

        int sourceNode = blockExitNode(currentBlock);
        var immediatelyDominatedNodes = domGraph.outgoingEdges(sourceNode);
        boolean ownsTrueBranch = ownsBranch(ifTrue);
        boolean ownsFalseBranch = ownsBranch(ifFalse);

        int childBlockCount = immediatelyDominatedNodes.length;
        if (ownsTrueBranch) {
            childBlockCount--;
        }
        if (ownsFalseBranch) {
            childBlockCount--;
        }
        var childBlocks = new BasicBlock[childBlockCount];
        int j = 0;
        for (var immediatelyDominatedNode : immediatelyDominatedNodes) {
            var childBlock = owningBlock(immediatelyDominatedNode);
            if (ownsTrueBranch && childBlock == ifTrue
                    || ownsFalseBranch && childBlock == ifFalse) {
                continue;
            }
            childBlocks[j++] = childBlock;
        }
        Arrays.sort(childBlocks, Comparator.comparing(b -> dfs[blockEnterNode(b)]));

        var blockStatements = new SimpleLabeledBlock[childBlockCount];
        for (int i = 0; i < childBlocks.length; i++) {
            var childBlock = childBlocks[i];
            var blockStatement = new SimpleLabeledBlock();
            jumpTargets[childBlock.getIndex()] = blockStatement;
            blockStatements[i] = blockStatement;
        }

        var firstChildBlock = childBlocks.length > 0 ? childBlocks[0] : null;
        var blockAfterIf = firstChildBlock != null ? firstChildBlock : nextBlock;
        var ifStatement = new IfBlock();

        ifStatement.condition = condition;

        ifStatement.thenBody = ownsTrueBranch
                ? processBlock(ifTrue, blockAfterIf)
                : getJumpStatement(ifTrue, blockAfterIf);

        ifStatement.elseBody = ownsFalseBranch
                ? processBlock(ifFalse, blockAfterIf)
                : getJumpStatement(ifFalse, blockAfterIf);

        optimizeIf(ifStatement);
        processBlockStatements(blockStatements, childBlocks, ifStatement);

        currentBlock = childBlocks.length > 0 ? childBlocks[childBlocks.length - 1] : null;
    }

    private void loopExitBranch(Instruction condition, boolean inverted, BasicBlock loopExit, BasicBlock next) {
        var ifStatement = new IfBlock();
        ifStatement.condition = condition;
        ifStatement.inverted = inverted;
        var breakStatement = new BreakBlock();
        breakStatement.target = loopExits[loopExit.getIndex()];
        ifStatement.thenBody = breakStatement;
        append(ifStatement);
        currentBlock = next;
    }

    private void switchBranch(SwitchInstruction instruction) {
        int sourceNode = blockExitNode(currentBlock);
        var immediatelyDominatedNodes = domGraph.outgoingEdges(sourceNode);
        var childBlockCount = immediatelyDominatedNodes.length;

        var targets = new LinkedHashSet<BasicBlock>(instruction.getEntries().size());
        for (var entry : instruction.getEntries()) {
            targets.add(entry.getTarget());
        }
        targets.add(instruction.getDefaultTarget());

        var isRegularBranch = new boolean[program.basicBlockCount()];
        for (var target : targets) {
            if (cfg.incomingEdgesCount(blockEnterNode(target)) == 1) {
                childBlockCount--;
                isRegularBranch[target.getIndex()] = true;
            }
        }

        var childBlocks = new BasicBlock[childBlockCount];
        int j = 0;
        for (var immediatelyDominatedNode : immediatelyDominatedNodes) {
            var childBlock = owningBlock(immediatelyDominatedNode);
            if (isRegularBranch[childBlock.getIndex()]) {
                continue;
            }
            childBlocks[j++] = childBlock;
        }
        Arrays.sort(childBlocks, Comparator.comparing(b -> dfs[blockEnterNode(b)]));

        var blockStatements = new SimpleLabeledBlock[childBlockCount];
        for (int i = 0; i < childBlocks.length; i++) {
            var childBlock = childBlocks[i];
            var blockStatement = new SimpleLabeledBlock();
            jumpTargets[childBlock.getIndex()] = blockStatement;
            blockStatements[i] = blockStatement;
        }

        var firstChildBlock = childBlocks.length > 0 ? childBlocks[0] : null;
        var blockAfterSwitch = firstChildBlock != null ? firstChildBlock : nextBlock;
        var switchStatement = new SwitchBlock();
        switchStatement.condition = instruction.getCondition();
        var entries = new ArrayList<SwitchBlockEntry>();

        var clausesByBlock = new SwitchClauseProto[program.basicBlockCount()];
        var clauses = new ArrayList<SwitchClauseProto>();
        for (var entry : instruction.getEntries()) {
            var clause = clausesByBlock[entry.getTarget().getIndex()];
            if (clause == null) {
                clause = new SwitchClauseProto(new SwitchBlockEntry());
                clausesByBlock[entry.getTarget().getIndex()] = clause;
                clauses.add(clause);
                entries.add(clause.entry);
                if (dom.dominates(sourceNode, blockEnterNode(entry.getTarget()))
                        && isRegularBranch[entry.getTarget().getIndex()]) {
                    clause.entry.body = processBlock(entry.getTarget(), blockAfterSwitch);
                } else {
                    clause.entry.body = getJumpStatement(entry.getTarget(), blockAfterSwitch);
                }
            }
            clause.conditions.add(entry.getCondition());
        }
        switchStatement.entries = List.of(entries.toArray(new SwitchBlockEntry[0]));

        if (dom.dominates(sourceNode, blockEnterNode(instruction.getDefaultTarget()))) {
            switchStatement.defaultBody = processBlock(instruction.getDefaultTarget(), blockAfterSwitch);
        } else {
            switchStatement.defaultBody = getJumpStatement(instruction.getDefaultTarget(), blockAfterSwitch);
        }

        for (var clause : clauses) {
            clause.entry.matchValues = clause.conditions.toArray();
        }

        processBlockStatements(blockStatements, childBlocks, switchStatement);

        currentBlock = childBlocks.length > 0 ? childBlocks[childBlocks.length - 1] : null;
    }

    private void processBlockStatements(SimpleLabeledBlock[] blockStatements, BasicBlock[] childBlocks,
            Block mainStatement) {
        if (blockStatements.length > 0) {
            var result = mainStatement;
            blockStatements[0].body = append(blockStatements[0].body, mainStatement);
            for (int i = 0; i < childBlocks.length - 1; ++i) {
                result = wrapWithLabeledStatement(result, blockStatements[i]);
                var next = processBlock(childBlocks[i], childBlocks[i + 1]);
                result = appendWithTryCatch(result, next);
            }
            result = wrapWithLabeledStatement(result, blockStatements[blockStatements.length - 1]);
            currentStatement = appendWithTryCatch(currentStatement, result);
        } else {
            append(mainStatement);
        }
    }

    private Block wrapWithLabeledStatement(Block statement, SimpleLabeledBlock blockStatement) {
        if (labeledBlockUsages.get(blockStatement) > 0) {
            blockStatement.body = statement;
            blockStatement.tryCatch = statement.tryCatch;
            optimizeConditionalBlock(blockStatement);
            statement = blockStatement;
        }
        return statement;
    }

    private static class SwitchClauseProto {
        final SwitchBlockEntry entry;
        final IntArrayList conditions = new IntArrayList();

        SwitchClauseProto(SwitchBlockEntry entry) {
            this.entry = entry;
        }
    }

    private boolean ownsBranch(BasicBlock branch) {
        if (loopExits[branch.getIndex()] != null || loops[branch.getIndex()] != null) {
            return false;
        }
        return dom.immediateDominatorOf(blockEnterNode(branch)) == blockExitNode(currentBlock)
                && enteringBlockCount(branch) == 1;
    }

    private void optimizeConditionalBlock(SimpleLabeledBlock statement) {
        while (optimizeFirstIfWithLastBreak(statement)) {
            // repeat
        }
    }

    private boolean optimizeFirstIfWithLastBreak(SimpleLabeledBlock statement) {
        if (statement.getBody() == null || !(statement.getBody() instanceof IfBlock)) {
            return false;
        }
        var nestedIf = (IfBlock) statement.getBody();
        if (nestedIf.thenBody == null) {
            return false;
        }
        var last = nestedIf.thenBody;
        if (!(last instanceof BreakBlock)) {
            return false;
        }
        if (((BreakBlock) last).getTarget() != statement) {
            return false;
        }
        nestedIf.thenBody = nestedIf.thenBody.previous;
        nestedIf.elseBody = append(nestedIf.elseBody, statement.next);
        decreaseUsage(statement);
        optimizeIf(nestedIf);
        return true;
    }

    private boolean optimizeIf(IfBlock statement) {
        return invertIf(statement);
    }

    private boolean invertIf(IfBlock statement) {
        if (statement.elseBody == null || statement.thenBody != null) {
            return false;
        }
        statement.inverted = !statement.inverted;
        statement.thenBody = statement.elseBody;
        statement.elseBody = null;
        return true;
    }

    private void exitCurrentDominator(BasicBlock target) {
        var jump = getJumpStatement(target, nextBlock);
        if (jump != null) {
            append(jump);
        }
        currentBlock = null;
    }

    private Block getJumpStatement(BasicBlock target, BasicBlock nextBlock) {
        if (target == nextBlock) {
            return null;
        }
        if (loops[target.getIndex()] != null) {
            var contStatement = new ContinueBlock();
            contStatement.target = loops[target.getIndex()];
            return contStatement;
        }
        var targetStatement = getJumpTarget(target);
        var breakStatement = new BreakBlock();
        breakStatement.target = targetStatement;
        breakStatement.tryCatch = currentStatement.tryCatch;
        return breakStatement;
    }

    private LabeledBlock getJumpTarget(BasicBlock target) {
        var targetStatement = jumpTargets[target.getIndex()];
        increaseUsage(targetStatement);
        return targetStatement;
    }

    private Block appendWithTryCatch(Block first, Block second) {
        if (first == null) {
            return second;
        }
        firstBlockToBalance = first;
        secondBlockToBalance = second;
        balanceTryCatch();
        first = firstBlockToBalance;
        second = secondBlockToBalance;
        firstBlockToBalance = null;
        secondBlockToBalance = null;
        return append(first, second.first);
    }

    private void balanceTryCatch() {
        if (firstBlockToBalance == null || secondBlockToBalance == null) {
            return;
        }
        collectTryCatchStack(firstBlockToBalance.tryCatch, firstTryCatchStack);
        collectTryCatchStack(secondBlockToBalance.tryCatch, secondTryCatchStack);

        var minSize = Math.min(firstTryCatchStack.size(), secondTryCatchStack.size());
        var commonSize = 0;
        while (commonSize < minSize
                && firstTryCatchStack.get(commonSize).sameAs(secondTryCatchStack.get(commonSize))) {
            ++commonSize;
        }

        firstBlockToBalance = popOld(firstBlockToBalance, firstTryCatchStack.size() - commonSize);
        secondBlockToBalance.tryCatch = firstBlockToBalance.tryCatch;

        for (var i = commonSize; i < secondTryCatchStack.size(); ++i) {
            var node = secondTryCatchStack.get(i);
            secondBlockToBalance.tryCatch = new TryCatchNode(node.exceptionType, node.variable,
                    node.handler, secondBlockToBalance, secondBlockToBalance.tryCatch);
        }

        firstTryCatchStack.clear();
        secondTryCatchStack.clear();
    }

    private Block firstBlockToBalance;
    private Block secondBlockToBalance;

    private void closeTryCatchSequence(Block block) {
        var first = block.first;
        if (first == block) {
            return;
        }
        while (block.tryCatch != first.tryCatch) {
            block = popOld(block, 1);
        }
    }

    private Block popOld(Block block, int count) {
        while (count-- > 0) {
            var tryBlock = new TryBlock();
            tryBlock.tryBlock = block;
            var previous = block.tryCatch.openingBlock.previous;
            if (previous != null) {
                block.tryCatch.openingBlock.detach();
            }
            var br = new BreakBlock();
            br.target = block.tryCatch.handler;
            increaseUsage(br.target);
            tryBlock.catchBlock = br;
            tryBlock.exception = block.tryCatch.variable;
            tryBlock.exceptionType = block.tryCatch.exceptionType;
            tryBlock.tryCatch = block.tryCatch.containing;
            block = append(previous, tryBlock);
        }
        return block;
    }

    private void collectTryCatchStack(TryCatchNode node, List<TryCatchNode> result) {
        while (node != null) {
            result.add(node);
            node = node.containing;
        }
        Collections.reverse(result);
    }

    private List<TryCatchNode> firstTryCatchStack = new ArrayList<>();
    private List<TryCatchNode> secondTryCatchStack = new ArrayList<>();

    private void increaseUsage(LabeledBlock block) {
        labeledBlockUsages.put(block, labeledBlockUsages.get(block) + 1);
    }

    private boolean decreaseUsage(LabeledBlock block) {
        var newCount = labeledBlockUsages.get(block) + 1;
        if (newCount == 0) {
            labeledBlockUsages.remove(block);
            return true;
        } else {
            labeledBlockUsages.put(block, newCount);
            return false;
        }
    }

    private InstructionVisitor instructionDecompiler = new AbstractInstructionVisitor() {
        @Override
        public void visit(BranchingInstruction insn) {
            branch(insn, insn.getConsequent(), insn.getAlternative());
        }

        @Override
        public void visit(BinaryBranchingInstruction insn) {
            branch(insn, insn.getConsequent(), insn.getAlternative());
        }

        @Override
        public void visit(JumpInstruction insn) {
            int sourceNode = blockExitNode(currentBlock);
            int targetNode = blockEnterNode(insn.getTarget());
            if (loopExits[insn.getTarget().getIndex()] != null
                    || dom.immediateDominatorOf(targetNode) != sourceNode) {
                exitCurrentDominator(insn.getTarget());
            } else {
                currentBlock = insn.getTarget();
            }
        }

        @Override
        public void visit(SwitchInstruction insn) {
            switchBranch(insn);
        }

        @Override
        public void visit(ExitInstruction insn) {
            var statement = new ReturnBlock();
            statement.value = insn.getValueToReturn();
            append(statement);
            currentBlock = null;
        }
    };
}
