package tl.antlr4;

import java.util.HashMap;
import java.util.Map;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

public class Main {
    public static void main(String[] args) {
        try {
            System.out.println("MAVA compiler. Made by Mantas Damijonaitis and Mantas Kleiva. All rights reserved. 2018.");
            if (args.length == 0) {
                System.out.println("No source code found! Please pass it like: mvn -q antlr4:antlr4 install exec:java -Dexec.args=\"test.mava\"");
                return;
            }
            TLLexer lexer = new TLLexer(CharStreams.fromFileName(args[0]));
            TLParser parser = new TLParser(new CommonTokenStream(lexer));
            parser.setBuildParseTree(true);
            ParseTree tree = parser.parse();
            
            Scope scope = new Scope();
            Map<String, Function> functions = new HashMap<>();
            SymbolVisitor symbolVisitor = new SymbolVisitor(functions);
            symbolVisitor.visit(tree);
            EvalVisitor visitor = new EvalVisitor(scope, functions);
            visitor.visit(tree);
        } catch (Exception e) {
            if (e.getMessage() != null) {
                System.err.println(e.getMessage());
            } else {
                e.printStackTrace();
            }
        }
    }
}
