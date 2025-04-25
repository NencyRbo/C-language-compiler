package frontend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class CodeGenerator {
    private List<PCode> codeList = new ArrayList<>();
    private Map<String, Integer> varAddressMap = new HashMap<>(); // 用于变量寻址
    public Map<String, Integer> funcEntryMap = new HashMap<>(); // 函数名到入口地址的映射
    private int nextVarAddress = 0; // 下一个可用的变量地址
    // ✅ 全局字符串池
    private Map<String, Integer> stringTable = new HashMap<>();
    public static List<String> stringPool = new ArrayList<>();

    // 你的原有变量...
    private List<PCode> pcodeList = new ArrayList<>();
    private int labelCount = 0;

    public List<PCode> generate(ASTNode node) {
        System.out.println("[DEBUG] CodeGenerator: 开始生成中间代码");
        visit(node);
        System.out.println("[DEBUG] CodeGenerator: 中间代码生成完成，共生成 " + codeList.size() + " 条指令");
        System.out.println("[DEBUG] 函数入口点映射: " + funcEntryMap);
        return codeList;
    }

    public String parseEscapes(String s) {
        return s
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    // ✅ 封装函数
    public int getStringIndex(String s) {
        String parsed = parseEscapes(s); // 处理转义字符
    
        if (!stringTable.containsKey(parsed)) {
            stringTable.put(parsed, stringPool.size());
            stringPool.add(parsed);
        }
        return stringTable.get(parsed);
    }

    private void emit(PCode inst, ASTNode node) {
        System.out.println("[PCode-DEBUG] 添加指令: " + inst + "  来自节点: " + node.getType() + 
            (node.getValue() != null ? ", 值: " + node.getValue() : ""));
        codeList.add(inst);
    }    

    private void visit(ASTNode node) {
        if (node == null) return;
        System.out.println("[DEBUG] 访问节点类型: " + node.getType() + (node.getValue() != null ? ", 值: " + node.getValue() : ""));

        switch (node.getType()) {
            case "Program":

            // CompUnit → {Decl} {FuncDef} MainFuncDef
            // CompUnit 是所有顶层声明（变量 + 函数 + 主函数）的总包装节点，相当于程序的根节点（Program）
            case "CompUnit":
                ASTNode mainFunc = null;
                List<ASTNode> funcDefs = new ArrayList<>();

                System.out.println("[DEBUG] 处理 CompUnit 节点，遍历子节点如下：");
                for (ASTNode child : node.getChildren()) {
                    String type = child.getType();
                    System.out.println("  ├─ 类型: " + type);
            
                    if ("FuncDef".equals(type)) {
                        funcDefs.add(child);
                        System.out.println("[DEBUG] 收集函数定义 FuncDef");
                    } else if ("MainFuncDef".equals(type)) {
                        if (mainFunc != null) {
                            System.err.println("[WARN] 检测到多个 MainFuncDef，忽略后续的！");
                        } else {
                            mainFunc = child;
                            System.out.println("[DEBUG] 捕获主函数 MainFuncDef");
                        }
                    } else {
                        System.out.println("[DEBUG] 处理声明节点 Decl");
                        visit(child);
                    }
                }

                for (ASTNode func : funcDefs) {
                    System.out.println("[DEBUG] visit 函数定义: " + func.getType());
                    System.out.println("🐾 AST 节点来源: " + func.getSource());  // 💥 就加在这里！
                    visit(func);
                }

                if (mainFunc != null) {
                    System.out.println("[DEBUG] 最后 visit 主函数 MainFuncDef");
                    visit(mainFunc);
                } else {
                    System.err.println("[ERROR] 没有检测到 MainFuncDef 主函数定义！");
                }

                break;


            case "Block":
                for (ASTNode child : node.getChildren()) {
                    visit(child);
                }
                break;

            case "MainFuncDef":
                String mainName = "main";
                int entryAddr_MainFunc = codeList.size();
                funcEntryMap.put(mainName, entryAddr_MainFunc); // ✅明确写入 "main" 的入口地址
                System.out.println("[DEBUG] 记录函数 'main' 的入口地址: " + entryAddr_MainFunc);
                
                System.out.println("[DEBUG] 进入主函数定义");
                // 访问 MainFuncDef 的 Block 子节点
                for (ASTNode child : node.getChildren()) {
                    if (child.getType().equals("Block")) {
                        visit(child);
                    }
                }
                // Main 函数结束后添加 RET 指令
                System.out.println("[DEBUG] 主函数结束，添加 RET 指令");
                if (codeList.isEmpty() || codeList.get(codeList.size() - 1).getOp() != PCode.OpCode.RET) {
                    emit(new PCode(PCode.OpCode.RET, 0, 0), node);
                }
                break;

            case "FuncDef":
                // 获取函数名
                // String funcName = node.getChildren().get(1).getValue();
                String funcName = null; // 🔍 初始为 null，待会儿从 AST 节点中动态提取函数名（因为构造 AST 时是 Token 封装进去的）
                
                // 访问函数体
                // 先提取 funcName
                for (ASTNode child : node.getChildren()) { // 🔁 遍历函数定义节点的所有子节点，寻找类型为 IDENFR 的子节点（它就是函数名）
                    if (child.getToken() != null && child.getToken().type == TokenType.IDENFR) {
                        funcName = child.getToken().value; // 🔍 找到 IDENFR 节点，提取其值作为函数名
                    }
                }
                // ❌ 如果遍历之后还是没找到函数名，那说明 AST 构造出 bug 了，直接报错！
                if (funcName == null) throw new RuntimeException("FuncDef 节点中未找到函数名");

                // 记录函数入口地址
                int entryAddr = codeList.size();// 🧾 当前代码生成器的指令列表位置，即函数入口地址
                // put 到 funcEntryMap 中，记录入口地址
                funcEntryMap.put(funcName, entryAddr);
                System.out.println("[DEBUG] 记录函数 '" + funcName + "' 的入口地址: " + entryAddr);
                
                // 然后继续访问函数体
                for (ASTNode child : node.getChildren()) {
                    if (child.getType().equals("Block")) {
                        visit(child); // ✅ 关键：访问函数体
                    }
                }

                // 确保函数以 RET 结束
                // 🛡️ 最后判断函数体是否已经生成 RET 指令，如果没有就补上
                // 这是防止某些函数体没有 return，导致栈悬空
                if (codeList.isEmpty() || codeList.get(codeList.size() - 1).getOp() != PCode.OpCode.RET) {
                    System.out.println("[DEBUG] 在函数 '" + funcName + "' 末尾添加 RET 指令");
                    emit(new PCode(PCode.OpCode.RET, 0, 0), node);
                }
                break;

            case "PrimaryExp":
                // 处理 PrimaryExp 的子节点
                for (ASTNode child : node.getChildren()) {
                    visit(child);
                }
                break;

            case "IDENFR":
                // 标识符节点，加载变量值
                String varName = node.getValue();
                int addr = getVarAddress(varName);
                System.out.println("[DEBUG] 生成 LOD 指令: 加载变量 " + varName + " (地址 " + addr + ")");
                emit(new PCode(PCode.OpCode.LOD, 0, addr), node);
                break;

            case "STRCON":
                // 字符串常量，目前仅支持输出
                // 在实际输出时会被 Printf 节点处理
                break;

            case "ConstDecl":
            case "VarDecl":
                // 可以忽略，因为在 symbol 表中已记录
                break;

            case "Decl":
            case "BType":
                // 类型信息不会影响中间代码
                break;

            case "Stmt":
                System.out.println("[DEBUG] 处理 Stmt");
                for (ASTNode child : node.getChildren()) {
                    visit(child);
                }
                break;

            case "AssignStmt":
                // 处理赋值语句：先计算右值，然后存储到左值
                visit(node.getChildren().get(1)); // 访问右值表达式
                ASTNode lval = node.getChildren().get(0);
                String name = lval.getChildren().get(0).getValue();
                int addrStore = getVarAddress(name);
                System.out.println("[DEBUG] 生成 STO 指令: 存储到变量 " + name + " (地址 " + addrStore + ")");
                emit(new PCode(PCode.OpCode.STO, 0, addrStore), node);
                // 赋值语句的值通常不留在栈上，STO 会消耗栈顶元素
                break;

            case "Exp":
                // 处理表达式
                for (ASTNode child : node.getChildren()) {
                    visit(child);
                }
                break;

            case "Getint":
                // 处理输入语句
                System.out.println("[DEBUG] 生成 READ 指令");
                emit(new PCode(PCode.OpCode.READ, 0, 0), node);

                // // 如果 Getint 是 GetintStmt 类型，说明需要手动存储
                // if (node.getChildren().size() > 0) {
                //     varName = node.getChildren().get(0).getValue(); // 变量名
                //     int address = getVarAddress(varName); // 找到变量地址
                //     System.out.println("[DEBUG] 生成 STO 指令: 存储输入值到变量 " + varName + " (地址 " + address + ")");
                //     emit(new PCode(PCode.OpCode.STO, 0, address), node); // 🔥 主动补上 STO
                // }
                // READ 指令会将读取的值压入栈顶
                // 如果 getint 是表达式的一部分，值留在栈上
                // 如果是 getint(); 这样的独立语句，可能需要根据文法决定是否丢弃值
                // 假设 getint() 的值需要存储到某个变量 (需要看具体文法和语义)
                // 如果是 `a = getint();`，则 AssignStmt 会处理 STO
                // 如果仅 `getint();`，可能不需要 STO，但这里可能缺失 LVal
                // 查找父节点是否为 AssignStmt，如果不是，可能需要特殊处理或报错
                // 假设 getint() 总是作为表达式求值，值留在栈上
                // *** 检查你的语法树结构和语义，确定 getint 的处理方式 ***
                // 之前的代码: codeList.add(new PCode(PCode.OpCode.STO, 0, getVarAddress(node.getChildren().get(0).getValue())));
                // 这似乎假设 Getint 节点有子节点代表要存储的变量，这可能不符合标准 C 文法
                // 暂时只生成 READ
                break;

            case "Printf":
                // 处理输出语句
                System.out.println("[DEBUG] 处理 Printf");

                // 判断第一个子节点是否是字符串常量
                ASTNode first = node.getChildren().get(0);
                if (first.getType().equals("STRCON")) {
                    String str = first.getValue();  // 获取STRCON值 比如 "21371295\n"
                    System.out.println("[DEBUG] 输出字符串常量: " + str);
                    // 如果你支持字符串输出，建议加入 PRINTSTR 指令（可自定义）
                    int idx = getStringIndex(str);
                    emit(new PCode(PCode.OpCode.PRINTSTR, 0, idx), node); // 🎯生成PRINTSTR指令
                }

                // 遍历参数表达式 Exp
                for (int i = 1; i < node.getChildren().size(); i++) { // 跳过 FormatString
                    ASTNode argExp = node.getChildren().get(i);
                    visit(argExp); // 计算参数表达式的值，结果压入栈
                    System.out.println("[DEBUG] 生成 PRINT 指令");
                    emit(new PCode(PCode.OpCode.PRINT, 0, 0), node);// PRINT 会消耗栈顶元素
                }
                break;

            case "Return":
                System.out.println("[DEBUG] 处理 Return");
                if (!node.getChildren().isEmpty()) {
                    ASTNode retExpr = node.getChildren().get(0); // 计算返回值表达式
                    System.out.println("🧠 Return 返回值节点类型: " + retExpr.getType());
                    visit(retExpr);
                    System.out.println("[DEBUG] Return 语句有返回值，值已计算到栈顶");
                }
                System.out.println("[DEBUG] 生成 RET 指令");
                emit(new PCode(PCode.OpCode.RET, 0, 0), node);
                break;

            case "AddExpr":
                System.out.println("[DEBUG] 处理 AddExpr");
                visit(node.getChildren().get(0));
                visit(node.getChildren().get(1));
                System.out.println("[DEBUG] 生成 ADD 指令");
                emit(new PCode(PCode.OpCode.ADD, 0, 0), node);
                break;

            case "SubExpr":
                System.out.println("[DEBUG] 处理 SubExpr");
                visit(node.getChildren().get(0));
                visit(node.getChildren().get(1));
                System.out.println("[DEBUG] 生成 SUB 指令");
                emit(new PCode(PCode.OpCode.SUB, 0, 0), node);
                break;

            case "MulExpr":
                System.out.println("[DEBUG] 处理 MulExpr");
                visit(node.getChildren().get(0));
                visit(node.getChildren().get(1));
                System.out.println("[DEBUG] 生成 MUL 指令");
                emit(new PCode(PCode.OpCode.MUL, 0, 0), node);
                break;

            case "DivExpr":
                System.out.println("[DEBUG] 处理 DivExpr");
                visit(node.getChildren().get(0));
                visit(node.getChildren().get(1));
                System.out.println("[DEBUG] 生成 DIV 指令");
                emit(new PCode(PCode.OpCode.DIV, 0, 0), node);
                break;

            case "MOD": // 假设取模节点类型为 "MOD"
                System.out.println("[DEBUG] 处理 MOD 表达式");
                visit(node.getChildren().get(0));
                visit(node.getChildren().get(1));
                System.out.println("[DEBUG] 生成 MOD 指令");
                emit(new PCode(PCode.OpCode.MOD, 0, 0), node);
                break;

            case "Number":
                System.out.println("[DEBUG] 处理 Number");
                for (ASTNode child : node.getChildren()) {
                    if (child.getType().equals("IntLiteral") || child.getType().equals("INTCON")) {
                        visit(child); // 访问 IntLiteral 或 INTCON
                        break; // Number 下通常只有一个常量
                    }
                }
                break;
        
            case "INTCON": // 处理整数常量 Token
            case "IntLiteral": // 或者处理整数常量 AST 节点
                String valStr = node.getValue();
                try {
                    int val = Integer.parseInt(valStr);
                    System.out.println("[DEBUG] 生成 LIT 指令: 加载常量 " + val);
                    emit(new PCode(PCode.OpCode.LIT, 0, val), node);
                } catch (NumberFormatException e) {
                    System.err.println("[ERROR] 无法解析整数常量: " + valStr);
                    // 可能需要添加错误处理或生成默认值
                    emit(new PCode(PCode.OpCode.LIT, 0, 0), node); // 错误时加载 0
                }
                break;

            case "LVal":
                // LVal 在赋值语句左侧由 AssignStmt 处理 (获取地址)
                // LVal 在表达式右侧表示加载值
                System.out.println("[DEBUG] 处理 LVal (作为右值)");
                String varNameRVal = node.getChildren().get(0).getValue(); // LVal -> IDENFR
                int addrRVal = getVarAddress(varNameRVal);
                System.out.println("[DEBUG] 生成 LOD 指令: 加载变量 " + varNameRVal + " (地址 " + addrRVal + ")");
                emit(new PCode(PCode.OpCode.LOD, 0, addrRVal), node);
                break;

            case "CallExpr":
                // 获取被调用的函数名
                String calledFuncName = node.getChildren().get(0).getValue(); // 第一个子节点是函数名
                System.out.println("[DEBUG] 处理函数调用: " + calledFuncName);
                List<ASTNode> args = node.getChildren().subList(1, node.getChildren().size());
                
                // // 处理参数（如果有）
                // for (int i = 1; i < node.getChildren().size(); i++) {
                //     visit(node.getChildren().get(i));
                // }

                // ✅ 1. 处理参数（已经有）
                for (ASTNode arg : args) {
                    visit(arg); // 每个参数压栈 ✅
                }

                // 🧩 2. 【新增】插入 STO 指令，把参数从栈存入函数作用域内存（bp + 0, bp + 1, ...）
                for (int i = args.size() - 1; i >= 0; i--) {
                    emit(new PCode(PCode.OpCode.STO, 0, i), node); // 从栈顶逆序存入
                }
                
                // ✅ 3. CALL 跳转 获取函数入口地址
                Integer funcAddr = funcEntryMap.get(calledFuncName);
                if (funcAddr == null) {
                    System.err.println("[ERROR] 找不到函数 '" + calledFuncName + "' 的入口地址");
                    throw new RuntimeException("未定义的函数: " + calledFuncName);
                }
                
                // 生成 CALL 指令
                System.out.println("[DEBUG] 生成 CALL 指令，跳转到函数 '" + calledFuncName + "' 的入口地址: " + funcAddr);
                emit(new PCode(PCode.OpCode.CALL, 0, funcAddr), node);
                break;

            // 处理一元表达式
            // 访问操作数：-Exp（负数取反）和 !Exp（逻辑非）UnaryExp → op + operand
            case "UnaryExp":
                List<ASTNode> unaryChildren = node.getChildren();

                if (unaryChildren.size() == 1) {
                    // ✅ 情况1：PrimaryExp 或函数调用（例如 sum(a,b)）
                    System.out.println("[DEBUG] UnaryExp 是 PrimaryExp 或函数调用，子节点数量 = 1");
                    visit(unaryChildren.get(0));

                } else if (unaryChildren.size() == 2) {
                    // ✅ 情况2：一元运算符，如 -Exp 或 !Exp
                    System.out.println("[DEBUG] UnaryExp 是一元运算，子节点数量 = 2");
                    String op = unaryChildren.get(0).getValue();
                    visit(unaryChildren.get(1)); // visit operand

                    switch (op) {
                        case "-":
                            System.out.println("[DEBUG] 生成 LIT 0 指令 (用于取反)");
                            emit(new PCode(PCode.OpCode.LIT, 0, 0), node);
                            System.out.println("[DEBUG] 生成 SWAP 指令 (用于取反)");
                            emit(new PCode(PCode.OpCode.SWAP, 0, 0), node);
                            System.out.println("[DEBUG] 生成 SUB 指令 (用于取反)");
                            emit(new PCode(PCode.OpCode.SUB, 0, 0), node);
                            break;
                        case "!":
                            System.out.println("[DEBUG] 生成 LIT 0 指令 (用于 NOT)");
                            emit(new PCode(PCode.OpCode.LIT, 0, 0), node);
                            System.out.println("[DEBUG] 生成 EQL 指令 (用于 NOT)");
                            emit(new PCode(PCode.OpCode.EQL, 0, 0), node);
                            break;
                        case "+":
                            System.out.println("[DEBUG] 一元 + 无需生成指令");
                            break;
                        default:
                            System.err.println("[ERROR] 未知的一元运算符: " + op);
                            break;
                    }

                } else {
                    // ❗结构异常
                    System.err.println("[ERROR] UnaryExp 子节点数量异常: " + unaryChildren.size());
                }
                break;


            case "UnaryOp": // UnaryOp 节点通常只包含操作符 Token
                // 不直接生成代码，由 UnaryExp 处理
                break;

            default:
                System.out.println("⚠️ CodeGenerator: 未处理的节点类型: " + node.getType() + "，尝试访问子节点...");
                // 兜底策略：尝试访问子节点，可能适用于某些容器型节点
                for (ASTNode child : node.getChildren()) {
                    visit(child);
                }
        }
    }

    private int getVarAddress(String varName) {
        if (varName == null || varName.isEmpty()) {
            System.err.println("[ERROR] CodeGenerator: 无效的变量名!");
            return -1; // 返回无效地址
        }

        // 如果变量已经有地址，直接返回
        if (varAddressMap.containsKey(varName)) {
            return varAddressMap.get(varName);
        }

        // 为新变量分配地址
        int address = nextVarAddress++;
        varAddressMap.put(varName, address);
        System.out.println("[DEBUG] 为变量 '" + varName + "' 分配新地址: " + address);
        return address;
    }
}
