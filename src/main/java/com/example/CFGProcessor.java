package com.example;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class CFGProcessor {
    private static int nodeId = 1;
    private static final Map<String, MethodDeclaration> methodMap = new HashMap<>();
    private static final List<String> graphLines = new LinkedList<>();
    private final LinkedList<Integer> endNodeStack = new LinkedList<>();

    public static void main(String[] args) {
        String filePath = "src/main/java/com/example/HelloWorld.java";
        try (FileInputStream in = new FileInputStream(filePath)) {

            ParseResult<CompilationUnit> parseResult = new JavaParser().parse(in);
            if (!parseResult.isSuccessful()) {
                throw new RuntimeException("Error parsing the file: " + filePath);
            }
            CompilationUnit cu = parseResult.getResult().get();

            graphLines.add("digraph G {");

            cu.accept(new DeclarationCollector(), null);

            // Process other methods if any
            methodMap.values().forEach(method -> {
                if (!method.getNameAsString().equals("main")) {
                    method.accept(new CFGVisitor(), null);
                }
            });

            if (methodMap.containsKey("main")) {
                graphLines.add("\tnode0 [label=\"Start\"];");
                // Directly connect the start node to the Main method node
                addNode("Method: main");
                methodMap.get("main").accept(new CFGVisitor(), null);
            }

            

            graphLines.add(String.format("\tnode%d [label=\"End\"];", nodeId));
            graphLines.add(String.format("\tnode%d -> node%d;", nodeId - 1, nodeId));
            graphLines.add("}");

            try (BufferedWriter writer = new BufferedWriter(new FileWriter("cfg.dot"))) {
                for (String line : graphLines) {
                    writer.write(line);
                    writer.newLine();
                }
            }

            System.out.println("Dot file generated: cfg.dot");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void addNode(String label) {
        String sanitizedLabel = label
            .replaceAll("\"", "\\\\\"")
            .replaceAll("\n", " ");

        graphLines.add(String.format("\tnode%d [label=\"%s\"];", nodeId, sanitizedLabel));
        if (nodeId > 0) { // Ensure we connect the start node to the main method node directly
            graphLines.add(String.format("\tnode%d -> node%d;", nodeId - 1, nodeId));
        }
        nodeId++;
    }

    private static void addSingleNode(String label) {
        String sanitizedLabel = label.replaceAll("\"", "\\\\\"").replaceAll("\n", " ");
        graphLines.add(String.format("\tnode%d [label=\"%s\"];", nodeId, sanitizedLabel));
        nodeId++;
    }
    

    private static class DeclarationCollector extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(MethodDeclaration n, Void arg) {
            methodMap.put(n.getNameAsString(), n);
            super.visit(n, arg);
        }
    }

    private static class CFGVisitor extends VoidVisitorAdapter<Void> {

    private final LinkedList<Integer> endNodesStack = new LinkedList<>();
        private boolean insideIfOrElseBlock = false;

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            // Method body is now visited directly without adding a separate node for the method declaration
            n.getBody().ifPresent(body -> body.accept(this, null));
        }

        @Override
        public void visit(ExpressionStmt n, Void arg) {
            if (insideIfOrElseBlock) {
                addSingleNode("Statement: " + n.toString());
            } else {
                addNode("Statement: " + n.toString());
            }
        }

        @Override
        public void visit(BlockStmt n, Void arg) {
            for (Statement stmt : n.getStatements()) {
                stmt.accept(this, arg);
            }
        }

        @Override
        public void visit(ForStmt n, Void arg) {
            // Combine the initialization, condition, and update parts of the for loop into a single label
            String forLoopLabel = "For Loop: Init=" + n.getInitialization() + ", Cond=" + n.getCompare().orElse(null) + ", Update=" + n.getUpdate();
            addNode(forLoopLabel);
            int forLoopNodeId = nodeId - 1; // The node ID of the for loop's condition

            // Process the body of the for loop, ensuring all statements inside are added
            n.getBody().accept(this, arg);

            // Optionally, if you want to represent the update part as a separate node (usually it's included in the for loop label)
            // addNode("For Update: " + n.getUpdate());
            // int forUpdateNodeId = nodeId - 1;
            // Connect the last statement of the loop body back to the update part, if represented separately
            // graphLines.add(String.format("\tnode%d -> node%d;", nodeId - 1, forUpdateNodeId));
            // Connect the update part back to the loop's condition to simulate the continuation condition
            // graphLines.add(String.format("\tnode%d -> node%d;", forUpdateNodeId, forLoopNodeId));

            // Connect the last statement inside the for loop back to the for loop condition to represent the loop continuation
            graphLines.add(String.format("\tnode%d -> node%d;", nodeId - 1, forLoopNodeId));

            // Add a node for the end of the for loop to represent the loop's exit point
            addSingleNode("End of For Loop");
            int endForNodeId = nodeId - 1;

            // Connect the for loop node directly to the end of the for loop node to simulate the control flow when the condition is false
            graphLines.add(String.format("\tnode%d -> node%d;", forLoopNodeId, endForNodeId));

        }

        @Override
        public void visit(SwitchStmt n, Void arg) {
            // Add a node for the switch statement's condition
            addNode("Switch: " + n.getSelector().toString());
            int switchNodeId = nodeId - 1;

            // Store the end nodes of each case for later connection to the "end switch" node
            List<Integer> caseEndNodeIds = new ArrayList<>();

            // Process each case in the switch statement
            for (SwitchEntry entry : n.getEntries()) {
                String label = entry.getLabels().isEmpty() ? "Default Case" : "Case: " + entry.getLabels().toString();
                addSingleNode(label);
                int caseNodeId = nodeId - 1; // Node ID of the current case entry
                graphLines.add(String.format("\tnode%d -> node%d;", caseNodeId, nodeId));

                // Connect the switch node to the current case node
                graphLines.add(String.format("\tnode%d -> node%d;", switchNodeId, caseNodeId));

                // Process the statements within the case
                if (!entry.getStatements().isEmpty()) {
                    insideIfOrElseBlock = true; // Disable automatic connection
                    entry.getStatements().forEach(stmt -> stmt.accept(this, arg));
                    insideIfOrElseBlock = false; // Re-enable automatic connection
                    // Store the last statement's node ID of the case for later connection to the "end switch" node
                    caseEndNodeIds.add(nodeId - 1);
                } else {
                    // For cases without statements (i.e., fall-through), connect directly to the next case/end switch
                    caseEndNodeIds.add(caseNodeId);
                }
            }

            // Add a node for the end of the switch statement
            addSingleNode("End of Switch");
            int endSwitchNodeId = nodeId - 1;

            // Connect all case end nodes to the "end switch" node
            caseEndNodeIds.forEach(caseEndNodeId -> graphLines.add(String.format("\tnode%d -> node%d;", caseEndNodeId, endSwitchNodeId)));

        }




        @Override
        public void visit(IfStmt n, Void arg) {
            insideIfOrElseBlock = true;
            int previousNodeId = nodeId - 1; // Keep track of the last node before the if statement
    
            addNode("Condition: " + n.getCondition().toString());
            int conditionNodeId = nodeId - 1; // Subtract one because addNode increments nodeId
    
            // Keep track of the last node ID within 'then' and 'else' branches
            int lastThenNodeId = -1;
            int lastElseNodeId = -1;
    
            // Process 'then' branch
            Statement thenStmt = n.getThenStmt();
            if (!isStatementEmpty(thenStmt)) {
                graphLines.add(String.format("\tnode%d -> node%d [label=\"True\"];", conditionNodeId, nodeId));
                thenStmt.accept(this, arg); // This will add all 'then' statements to the graph
                lastThenNodeId = nodeId - 1; // Update the last node ID for the 'then' branch
            }
    
            // Process 'else' branch
            if (n.getElseStmt().isPresent()) {
                Statement elseStmt = n.getElseStmt().get();
                if (!isStatementEmpty(elseStmt)) {
                    graphLines.add(String.format("\tnode%d -> node%d [label=\"False\"];", conditionNodeId, nodeId));
                    elseStmt.accept(this, arg); // This will add all 'else' statements to the graph
                    lastElseNodeId = nodeId - 1; // Update the last node ID for the 'else' branch
                }
            }
    
            // Now add the 'End-if' node
            addSingleNode("End of If-Else Block");
            int endIfNodeId = nodeId - 1;
    
            // Connect the last node of 'then' branch to the 'end-if' node, if 'then' was not empty
            if (lastThenNodeId != -1) {
                graphLines.add(String.format("\tnode%d -> node%d;", lastThenNodeId, endIfNodeId));
            } else {
                // If 'then' was empty, connect the condition node directly to the 'end-if' node
                //graphLines.add(String.format("\tnode%d -> node%d [label=\"True\"];", conditionNodeId, endIfNodeId));
            }
    
            // Connect the last node of 'else' branch to the 'end-if' node, if 'else' was not empty
            if (lastElseNodeId != -1) {
                graphLines.add(String.format("\tnode%d -> node%d;", lastElseNodeId, endIfNodeId));
            } else if (n.getElseStmt().isPresent()) {
                // If 'else' was empty, still need to connect the condition node to the 'end-if' node
                //graphLines.add(String.format("\tnode%d -> node%d [label=\"False\"];", conditionNodeId, endIfNodeId));
            }
    
            // Connect the 'end-if' node to the next node (which will be the next statement after the if-else)
            // Note that this next node is yet to be added, so we use endIfNodeId + 1 as a placeholder
            //graphLines.add(String.format("\tnode%d -> node%d;", endIfNodeId, endIfNodeId + 1));
            nodeId = endIfNodeId + 1; // Increment nodeId to be used for the next actual statement
            insideIfOrElseBlock = false;
        }
    

        private boolean isStatementEmpty(Statement stmt) {
            // This method checks if a given statement is effectively empty.
            if (stmt instanceof BlockStmt) {
                return ((BlockStmt) stmt).getStatements().isEmpty();
            }
            // Consider additional types of statements here if necessary
            return stmt.toString().trim().isEmpty();
        }

        @Override
        public void visit(WhileStmt n, Void arg) {
            // Add a node for the while loop's condition
            addNode("While: " + n.getCondition().toString());
            int whileConditionNodeId = nodeId - 1; // Remember the node ID of the while condition

            // Process the body of the while loop, ensuring all statements inside are added
            // Temporarily disable automatic connection to simulate linear control flow inside the while loop
            n.getBody().accept(this, arg);

            int lastStatementInsideWhileNodeId = nodeId - 1; // Node ID of the last statement inside the while loop

            // Connect the last statement inside the while loop back to the condition
            graphLines.add(String.format("\tnode%d -> node%d;", lastStatementInsideWhileNodeId, whileConditionNodeId));

            // Add a node for the end of the while loop to represent the loop's exit point
            addSingleNode("End of While Loop");
            int endWhileNodeId = nodeId - 1;

            // Connect the while condition directly to the end-while node to simulate the control flow when the condition is false
            graphLines.add(String.format("\tnode%d -> node%d;", whileConditionNodeId, endWhileNodeId));

            // The next node to be added will be the statement immediately outside the while loop
            // Note: At this point, nodeId has already been incremented by addSingleNode,
            // so the connection to the next statement will be added when the next statement node is created.
        }

        @Override
        public void visit(DoStmt n, Void arg) {
            // Add a node for entering the do-while loop
            addNode("Enter Do-While Loop");
            int enterDoWhileNodeId = nodeId - 1;

            // Process the body of the do-while loop, ensuring all statements inside are added
            n.getBody().accept(this, arg);

            // Add a node for the do-while loop's condition
            addNode("Do-While Condition: " + n.getCondition().toString());
            int doWhileConditionNodeId = nodeId - 1;

            // Connect the condition back to the loop's start to represent the potential for looping
            graphLines.add(String.format("\tnode%d -> node%d;", doWhileConditionNodeId, enterDoWhileNodeId));

            // Add a node for the end of the do-while loop to represent the loop's exit point
            addSingleNode("End of Do-While Loop");
            int endDoWhileNodeId = nodeId - 1;

            // Connect the do-while condition to the end of the do-while node, representing the exit condition
            graphLines.add(String.format("\tnode%d -> node%d;", doWhileConditionNodeId, endDoWhileNodeId));

        }

    }
    
}

