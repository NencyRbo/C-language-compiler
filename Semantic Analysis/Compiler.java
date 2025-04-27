import frontend.*;
import frontend.Error;

import java.io.*;
import java.util.*;

public class Compiler {
    public static void main(String[] args) throws Exception {
        // ✅ 将输入重定向为 input.txt
        System.setIn(new FileInputStream("data/input.txt"));
        System.out.println("Step 1: 开始词法分析...");
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize("data/testfile.txt");
        System.out.println("词法分析完成，Token数: " + tokens.size());

        // 收集错误
        List<Error> errors = lexer.errors;
        Parser parser = new Parser(tokens, errors, lexer.errorLines);

        System.out.println("Step 2: 开始语法分析...");
        ASTNode ast = parser.parse(); // ← parse 现在有返回值
        System.out.println(ast);
        System.out.println("语法分析完成");

        // 错误排序输出
        errors.sort(Comparator.comparingInt(e -> e.lineNumber));
        try (PrintWriter writer = new PrintWriter("data/error.txt")) {
            Set<Integer> outputtedLines = new HashSet<>();
            for (Error error : errors) {
                if (!outputtedLines.contains(error.lineNumber)) {
                    writer.println(error.lineNumber + " " + error.errorType);
                    outputtedLines.add(error.lineNumber);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("错误信息已写入 error.txt（如有）");

        if (errors.isEmpty()) {
            System.out.println("无语法错误，继续生成 symbol.txt / pcode.txt / pcoderesult.txt");

            // 输出符号表
            try (PrintWriter writer = new PrintWriter("data/symbol.txt")) {
                parser.symbolList.sort(Comparator.comparingInt(s -> s.scopeLevel));
                for (Symbol symbol : parser.symbolList) {
                    writer.println(symbol.scopeLevel + " " + symbol.name + " " + symbol.type);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("符号表已写入 symbol.txt");

            // 生成中间代码
            System.out.println("Step 3: 生成中间代码...");
            CodeGenerator codeGen = new CodeGenerator();
            List<PCode> pcodes = codeGen.generate(ast);
            System.out.println("中间代码生成完成，指令数: " + pcodes.size());

            // 写入 pcode.txt
            try (PrintWriter writer = new PrintWriter("data/pcode.txt")) {
                for (PCode code : pcodes) {
                    writer.println(code);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("中间代码已写入 pcode.txt");

            // 执行中间代码
            System.out.println("Step 4: 执行 PCode...");
            PCodeExecutor executor = new PCodeExecutor(pcodes);

            // ✅ 添加这段代码：
            Integer entry = codeGen.funcEntryMap.get("main");
            if (entry == null) throw new RuntimeException("没有找到 main 函数的入口地址！");
            executor.setPC(entry);
            
            executor.execute(); // 自动写入 pcoderesult.txt
            System.out.println("执行完成，结果已写入 pcoderesult.txt");
        } else {
            System.out.println("存在语法错误，跳过中间代码生成与执行");
        }

        System.out.println("所有流程执行完毕！");
    }
}
