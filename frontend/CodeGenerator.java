package frontend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import frontend.PCode.OpCode;

import java.util.HashMap;

public class CodeGenerator {
    private List<PCode> codeList = new ArrayList<>();
    private List<PCode> globalInitCodeList = new ArrayList<>(); // <-- 新增：存储全局初始化指令
    // private Map<String, Integer> varAddressMap = new HashMap<>(); // 用于变量寻址
    private Stack<Map<String, Symbol>> symbolTableStack = new Stack<>(); // ✅ 作用域栈
    private Map<String, Symbol> globalSymbolTable = new HashMap<>(); // ✅ 全局符号表
    
    public Map<String, Integer> funcEntryMap = new HashMap<>(); // 函数名到入口地址的映射
    // private int nextVarAddress = 0; // 下一个可用的变量地址
    // ✅ 全局字符串池
    private Map<String, Integer> stringTable = new HashMap<>();
    public static List<String> stringPool = new ArrayList<>();
    private Map<Integer, Integer> labelAddressMap = new HashMap<>(); // if和for用的回填地址表
    private boolean isGeneratingGlobalInit = false; // <-- 新增：标记是否正在生成全局初始化代码

    // 你的原有变量...
    // private List<PCode> pcodeList = new ArrayList<>();
    private int labelCount = 0;

    private Stack<Integer> exitLabelStack = new Stack<>();
    private Stack<Integer> stepLabelStack = new Stack<>();

    // 全局变量的层级，假设为 -1
    public static final int GLOBAL_LEVEL = -1;

    public void registerSymbol(Symbol symbol) {
        if (symbol.level == GLOBAL_LEVEL) {
            if (globalSymbolTable.containsKey(symbol.name)) {
                System.err.println("[WARN] 全局符号 '" + symbol.name + "' 已存在，将被覆盖！");
            }
            globalSymbolTable.put(symbol.name, symbol);
            System.out.println("[DEBUG] 注册全局符号: " + symbol.name +
                ", level=" + symbol.level +
                ", offset=" + symbol.offset);
        } else {
            if (symbolTableStack.isEmpty()) {
                throw new RuntimeException("错误：尝试在没有局部作用域的情况下注册局部符号 '" + symbol.name + "'");
            }
            Map<String, Symbol> currentScope = symbolTableStack.peek();
            if (currentScope.containsKey(symbol.name)) {
                System.err.println("[WARN] 当前作用域已存在符号 '" + symbol.name + "'，将被覆盖！");
            }
            currentScope.put(symbol.name, symbol);
            System.out.println("[DEBUG] 注册局部符号: " + symbol.name +
                ", level=" + symbol.level +
                ", offset=" + symbol.offset + " 到当前作用域");
        }
    }

    public List<PCode> generate(ASTNode node) {
        System.out.println("[DEBUG] CodeGenerator: 开始生成中间代码");
        visit(node);

        // visit结束后统一回填
        patchLabels();  

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
        if (isGeneratingGlobalInit) { // <-- 修改：根据标记决定添加到哪个列表
            globalInitCodeList.add(inst);
        } else {
            codeList.add(inst);
        }
    }    

    private Symbol getSymbol(String varName) {
        // 1. 从栈顶向栈底查找局部作用域
        for (int i = symbolTableStack.size() - 1; i >= 0; i--) {
            Map<String, Symbol> scope = symbolTableStack.get(i);
            if (scope.containsKey(varName)) {
                System.out.println("[DEBUG] 在作用域栈 level " + i + " 找到符号: " + varName);
                return scope.get(varName);
            }
        }

        // 2. 如果局部作用域都找不到，查找全局作用域
        if (globalSymbolTable.containsKey(varName)) {
            System.out.println("[DEBUG] 在全局作用域找到符号: " + varName);
            return globalSymbolTable.get(varName);
        }

        // 3. 如果全局也找不到，则抛出错误
        throw new RuntimeException("变量未定义或在当前作用域不可见: " + varName);
    }

    private void visit(ASTNode node) {
        if (node == null) return;
        System.out.println("[DEBUG] 访问节点类型: " + node.getType() + (node.getValue() != null ? ", 值: " + node.getValue() : ""));

        switch (node.getType()) {
            case "Program":

            // CompUnit → {Decl} {FuncDef} MainFuncDef
            // CompUnit 是所有顶层声明（变量 + 函数 + 主函数）的总包装节点，相当于程序的根节点（Program）
            case "CompUnit":
                // 初始化符号表栈，压入一个空的全局基础作用域（全局变量实际存储在 globalSymbolTable）
                symbolTableStack.clear();
                globalSymbolTable.clear();
                symbolTableStack.push(new HashMap<>()); // 压入一个基础作用域
                System.out.println("[DEBUG] 初始化符号表栈和全局符号表");

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
                        System.out.println("[DEBUG] 处理全局声明节点 Decl");
                        isGeneratingGlobalInit = true; // <-- 设置标记
                        visit(child);
                        isGeneratingGlobalInit = false; // <-- 清除标记
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
                System.out.println("[DEBUG] 进入 Block 作用域");
                symbolTableStack.push(new HashMap<>()); // 进入新作用域
                System.out.println("[DEBUG] 处理 Block（交错顺序遍历语句与变量声明）");
                for (ASTNode child : node.getChildren()) {
                    System.out.println("[DEBUG] 遍历子节点: " + child.getType());
                    visit(child);
                }
                symbolTableStack.pop(); // 退出作用域
                System.out.println("[DEBUG] 退出 Block 作用域");
                break;

            case "MainFuncDef":
                String mainName = "main";
                int entryAddr_MainFunc = codeList.size();
                funcEntryMap.put(mainName, entryAddr_MainFunc); // ✅ 记录 "main" 的入口地址（包含全局初始化）
                System.out.println("[DEBUG] 记录函数 'main' 的入口地址: " + entryAddr_MainFunc);

                // <-- 新增：在 main 函数代码前插入全局初始化指令
                System.out.println("[DEBUG] 在 main 函数前插入全局初始化指令，共 " + globalInitCodeList.size() + " 条");
                codeList.addAll(globalInitCodeList);
                // <-- 结束新增
                
                System.out.println("[DEBUG] 进入主函数定义");
                symbolTableStack.push(new HashMap<>()); // 进入 main 函数作用域
                // 访问 MainFuncDef 的 Block 子节点
                for (ASTNode child : node.getChildren()) {
                    if (child.getType().equals("Block")) {
                        visit(child);
                    }
                }
                symbolTableStack.pop(); // 退出 main 函数作用域
                System.out.println("[DEBUG] 退出 main 函数作用域");
                // Main 函数结束后添加 RET 指令
                System.out.println("[DEBUG] 主函数结束，添加 RET 指令");
                if (codeList.isEmpty() || codeList.get(codeList.size() - 1).getOp() != PCode.OpCode.RET) {
                    emit(new PCode(PCode.OpCode.RET, 0, 0), node);
                }
                break;

            case "FuncDef":
                System.out.println("[DEBUG][FuncDef] 开始处理函数定义节点");
                // 获取函数名
                // String funcName = node.getChildren().get(1).getValue();
                String funcName = null; // 🔍 初始为 null，待会儿从 AST 节点中动态提取函数名（因为构造 AST 时是 Token 封装进去的）
                ASTNode funcFParamsNode = null;
                ASTNode blockNode = null;

                // 访问函数体
                // 先提取 funcName
                for (ASTNode child : node.getChildren()) { // 🔁 遍历函数定义节点的所有子节点，寻找类型为 IDENFR 的子节点（它就是函数名）
                    System.out.println("[DEBUG][FuncDef] 遍历子节点，当前子节点类型: " + child.getType());

                    if (child.getToken() != null && child.getToken().type == TokenType.IDENFR) {
                        funcName = child.getToken().value; // 🔍 找到 IDENFR 节点，提取其值作为函数名
                        System.out.println("[DEBUG][FuncDef] 找到函数名节点，函数名: " + funcName);
                    }
                    if ("FuncFParams".equals(child.getType())) {
                        funcFParamsNode = child;
                        System.out.println("[DEBUG][FuncDef] 找到形参列表 FuncFParams 节点");
                    }
                    if ("Block".equals(child.getType())) {
                        blockNode = child;
                        System.out.println("[DEBUG][FuncDef] 找到函数体 Block 节点");
                    }
                }
                // ❌ 如果遍历之后还是没找到函数名，那说明 AST 构造出 bug 了，直接报错！
                if (funcName == null) {
                    System.err.println("[ERROR][FuncDef] 遍历结束但未找到函数名！");
                    throw new RuntimeException("FuncDef节点中未找到函数名！");
                }

                // 记录函数入口地址
                int entryAddr = codeList.size();// 🧾 当前代码生成器的指令列表位置，即函数入口地址
                // put 到 funcEntryMap 中，记录入口地址
                funcEntryMap.put(funcName, entryAddr);
                System.out.println("[DEBUG] 记录函数 '" + funcName + "' 的入口地址: " + entryAddr);
                // Register the function name itself as a global symbol
                System.out.println("[DEBUG][FuncDef] Creating symbol for function '" + funcName + "'");
                Symbol funcSymbol = new Symbol(funcName, "function", GLOBAL_LEVEL); // Functions are global
                // funcSymbol.offset = entryAddr; // Let's not store address in offset for now, might conflict
                registerSymbol(funcSymbol); // Register in globalSymbolTable
                System.out.println("[DEBUG][FuncDef] Registered function symbol '" + funcName + "' globally.");
                
                System.out.println("[DEBUG] 进入函数 '" + funcName + "' 作用域");
                symbolTableStack.push(new HashMap<>()); // 进入函数作用域
                
                // 然后继续访问函数体
                // boolean insertedParamCopy = false;
                // for (ASTNode child : node.getChildren()) {
                //     if (child.getType().equals("FuncFParams")) {
                //         // 处理函数形参列表
                //         int paramIndex = 0;
                //         for (ASTNode param : child.getChildren()) {
                //             // 形参名字一般在FuncFParam下面
                //             ASTNode identNode = param.getChildren().get(0); // BType int/char, Ident, 可选[]
                //             if (param.getChildren().get(0).getType().equals("IDENFR")) {
                //                 identNode = param.getChildren().get(0);
                //             } else {
                //                 // 说明第一个是BType类型，第二个是IDENFR
                //                 identNode = param.getChildren().get(1);
                //             }
                //             String paramName = identNode.getValue();
                //             // **先给形参分配一个局部变量地址**
                //             int addr = getVarAddress(paramName);
                //             System.out.println("[DEBUG] 为形参 '" + paramName + "' 分配局部地址 " + addr);

                //             // **生成把栈上的参数拷贝到局部地址的pcode**
                //             // 插入：LOD paramIndex
                //             emit(new PCode(PCode.OpCode.LOD, 0, paramIndex), node);
                //             // 插入：STO addr
                //             emit(new PCode(PCode.OpCode.STO, 0, addr), node);
                //             System.out.println("[DEBUG] 插入参数拷贝: 形参 " + paramName + " 参数位置 " + paramIndex + " 存到地址 " + addr);
                //             paramIndex++;
                //         }
                //         // insertedParamCopy = true;
                //     }

                //     if (child.getType().equals("Block")) {
                //         visit(child); // ✅ 关键：访问函数体
                //     }
                // }

                // 如果有形参列表，处理形参
                if (funcFParamsNode != null) {
                    System.out.println("[DEBUG][FuncDef] 开始处理函数形参");
                
                    int paramIndex = 0;
                    for (ASTNode paramNode : funcFParamsNode.getChildren()) {
                        if (paramNode.getChildren().size() > 0) {
                            ASTNode identNode = paramNode.getChildren().get(0);
                            String paramName = identNode.getValue();
                            System.out.println("[DEBUG][FuncDef] 处理形参 '" + paramName + "'");
                
                            // ✅ 构造 Symbol 对象（必须！）
                            // 参数的层级是当前函数的层级，即 0 (相对于全局-1)
                            Symbol sym = new Symbol(paramName, "int", 0); 
                            // 参数在栈帧中的偏移量，需要跳过 SL, DL, RA (假设它们占3个位置)
                            sym.offset = paramIndex + 3; 
                            System.out.println("[DEBUG][FuncDef] 构造形参 Symbol: name=" + sym.name + ", type=" + sym.type + ", level=" + sym.level + ", offset=" + sym.offset);

                            sym.isParam = true; // 形参注册处设置为 true
                
                            // ✅ 注册 symbol（关键！）
                            registerSymbol(sym);
                            System.out.println("[DEBUG][FuncDef] 注册形参 '" + paramName + "' 到 symbol 表");
                
                            // ✅ 生成 STO 指令，将调用者压入数据栈的参数值弹出，并存入当前函数栈帧的正确偏移量位置
                            // STO 的 level 是 0，因为是存储到当前活动记录（栈帧）中
                            emit(new PCode(PCode.OpCode.STO, 0, sym.offset), node);
                            System.out.println("[DEBUG][FuncDef] 生成 STO 指令: 将栈顶参数存入内存地址 bp + " + sym.offset + " (对应形参 '" + sym.name + "')");
                
                            // System.out.println("[DEBUG][FuncFParams] 注册并处理形参 '" + paramName + "', level=" + sym.level + ", offset=" + sym.offset);
                
                            paramIndex++;
                        }
                    }
                
                    System.out.println("[DEBUG][FuncFParams] 所有形参处理完毕");
                }
                 else {
                    System.out.println("[DEBUG][FuncDef] 没有形参列表，跳过参数处理");
                }

                // 最后访问Block
                if (blockNode != null) {
                    System.out.println("[DEBUG][FuncDef] 开始访问函数体 Block");
                    visit(blockNode);
                    System.out.println("[DEBUG][FuncDef] 函数体 Block 访问完成");
                } else {
                    System.err.println("[ERROR][FuncDef] 未找到函数体Block！");
                    throw new RuntimeException("FuncDef节点中找不到Block！");
                }

                // 确保函数以 RET 结束
                // 🛡️ 最后判断函数体是否已经生成 RET 指令，如果没有就补上
                // 这是防止某些函数体没有 return，导致栈悬空
                if (codeList.isEmpty() || codeList.get(codeList.size() - 1).getOp() != PCode.OpCode.RET) {
                    System.out.println("[DEBUG] 在函数 '" + funcName + "' 末尾添加 RET 指令");
                    emit(new PCode(PCode.OpCode.RET, 0, 0), node);
                    System.out.println("[DEBUG][FuncDef] 函数末尾无RET指令，自动补充");
                } else {
                    System.out.println("[DEBUG][FuncDef] 函数末尾已有RET指令，无需补充");
                }
                symbolTableStack.pop(); // 退出函数作用域
                System.out.println("[DEBUG] 退出函数 '" + funcName + "' 作用域");
                break;

            case "PrimaryExp":
                // 处理 PrimaryExp 的子节点
                for (ASTNode child : node.getChildren()) {
                    visit(child);
                }
                break;

            case "IDENFR":
                System.out.println("[DEBUG] 处理 IDENFR 节点");
                // 标识符节点，加载变量值
                String varName = node.getValue();
                System.out.println("[DEBUG] 处理标识符: " + varName);
                // int addr = getVarAddress(varName);
                Symbol sym = getSymbol(varName);
                System.out.println("[DEBUG] 标识符 '" + varName + "' 的地址: " + sym.offset);
                System.out.println("[DEBUG] 生成 LOD 指令: 加载变量 " + varName + " (地址 " + sym.offset + ")");
                // emit(new PCode(OpCode.LOD, sym.level, sym.offset + (sym.isParam ? 1 : 0)), node);

                int finalOffset = sym.offset;
                System.out.println("[DEBUG] 标识符 '" + varName + "' 的最终偏移量: " + finalOffset);
                // // 仅当符号是局部变量 (level != -1) 且是参数 (isParam) 时，才将偏移量加 1
                // if (sym.level != GLOBAL_LEVEL && sym.isParam) { 
                //     finalOffset += 1;
                // }
                System.out.println("[DEBUG] 标识符 '" + varName + "' 的最终偏移量 (考虑参数): " + finalOffset);
                emit(new PCode(OpCode.LOD, sym.level, finalOffset), node);
                System.out.println("[DEBUG] 生成 LOD 指令: 加载变量 " + varName + " (地址 " + finalOffset + ")");

                break;

            case "STRCON":
                // 字符串常量，目前仅支持输出
                // 在实际输出时会被 Printf 节点处理
                break;

            case "InitVal":
                System.out.println("[DEBUG] 处理 InitVal 节点");
                for (ASTNode child : node.getChildren()) {
                    System.out.println("[DEABUG] 处理 InitVal 节点，子节点数: " + node.getChildren().size());
                    visit(child); // 直接访问 InitVal 内部的 Exp 节点
                }
                break;

            case "VarDef":
                System.out.println("[DEBUG] VarDef 节点占位，目前由 VarDecl 统一处理。未来支持数组 InitVal 时可能在此扩展。");
                break;

            case "ConstDecl":
                System.out.println("[DEBUG] Processing ConstDecl node");
                // 一个 ConstDecl 节点包含一个或多个 ConstDef 子节点
                for (ASTNode constDefNode : node.getChildren()) {
                    // 确保当前处理的是 ConstDef 节点
                    if (!"ConstDef".equals(constDefNode.getType())) {
                        System.err.println("[WARN] Skipping unexpected child type under ConstDecl: " + constDefNode.getType());
                        continue;
                    }
                    System.out.println("[DEBUG] Processing ConstDef child node");
            
                    ASTNode identNode = null;
                    ASTNode constInitValNode = null;
            
                    // 在 ConstDef 节点中查找标识符 (IDENFR) 和常量初始值 (ConstInitVal) 节点
                    for (ASTNode child : constDefNode.getChildren()) {
                        // 检查节点是否为标识符 Token
                        if (child.getToken() != null && child.getToken().type == TokenType.IDENFR) {
                            identNode = child;
                        }
                        // 检查节点是否为常量初始值
                        else if ("ConstInitVal".equals(child.getType())) {
                            constInitValNode = child;
                        }
                    }
            
                    // 检查是否成功找到了标识符和初始值节点
                    if (identNode == null || constInitValNode == null) {
                        System.err.println("[ERROR] Malformed ConstDef node: Missing Identifier or ConstInitVal. Skipping.");
                        continue;
                    }
            
                    String constName = identNode.getValue();
                    System.out.println("[DEBUG] Found constant definition for: " + constName);
            
                    // --- 关键步骤 1: 计算常量值 --- 
                    // 访问 ConstInitVal 节点。这应该递归地触发对其子节点 (ConstExp -> AddExp -> ... -> Number) 的访问，
                    // 最终目的是在 PCode 虚拟机的栈顶留下计算好的常量值。
                    // **请确保 ConstInitVal, ConstExp, AddExp, Number 等节点的 visit 方法能正确求值并将结果压栈**
                    System.out.println("[DEBUG] Visiting ConstInitVal to compute value for " + constName);
                    visit(constInitValNode); 
                    // 假设执行完 visit(constInitValNode) 后，常量值已经在栈顶
            
                    // --- 关键步骤 2: 注册符号并获取信息 --- 
                    // 检查符号是否已在全局表中定义 (常量只能在全局定义)
                    if (globalSymbolTable.containsKey(constName)) {
                        System.err.println("[ERROR] 全局常量 '" + constName + "' 重复定义！");
                        // 弹出已计算的值，避免影响后续指令
                        // emit(new PCode(PCode.OpCode.POP, 0, 1), identNode); // 假设有 POP 指令
                        continue; // 跳过此常量
                    }
                    // Determine level and offset based on stack state
                    int currentLevelConst;
                    int offsetConst;
                    if (symbolTableStack.isEmpty()) { // Expect stack to be empty for globals
                        currentLevelConst = GLOBAL_LEVEL; // -1
                        offsetConst = globalSymbolTable.size(); // Global offset
                    } else {
                        // This case shouldn't happen for 'const' in this grammar
                        System.err.println("[ERROR] Unexpected non-empty stack during ConstDef for " + constName);
                        currentLevelConst = GLOBAL_LEVEL; // Fallback to global
                        offsetConst = globalSymbolTable.size();
                    }

                    // 创建并注册新的全局常量符号
                    Symbol symNew = new Symbol(constName, "const", currentLevelConst); // Use calculated level
                    symNew.offset = offsetConst; // Use calculated offset
                    symNew.isConst = true; // Mark as constant
                    registerSymbol(symNew); // 注册符号
                    System.out.println("[DEBUG] Registered global constant: " + constName + ": level=" + symNew.level + ", offset=" + symNew.offset);
                    
                    // --- 关键步骤 3: 生成存储指令 --- 
                    System.out.println("[DEBUG] Emitting STO instruction for " + constName + " at level " + symNew.level + ", offset " + symNew.offset);
                    emit(new PCode(PCode.OpCode.STO, symNew.level, symNew.offset), constDefNode);
            
                    System.out.println("[DEBUG] Finished processing definition for constant: " + constName);
                }
                System.out.println("[DEBUG] Finished processing ConstDecl node");
                break;
            
            
            case "ConstInitVal":
                System.out.println("[DEBUG] 处理 ConstInitVal 节点");
                // ConstInitVal should have one child: ConstExp or an array initializer
                // For simple constants, it's ConstExp
                if (!node.getChildren().isEmpty()) {
                    visit(node.getChildren().get(0)); // Visit the child expression
                } else {
                     System.err.println("[WARN] ConstInitVal has no children!");
                     // Maybe push a default value like 0? Or let it fail?
                     // For now, just log it. The subsequent STO might fail if stack is empty.
                }
                break;

            case "ConstExp":
                System.out.println("[DEBUG] 处理 ConstExp 节点");
                // ConstExp should have one child: AddExp
                if (!node.getChildren().isEmpty()) {
                    visit(node.getChildren().get(0)); // Visit the child expression (AddExp)
                } else {
                    System.err.println("[WARN] ConstExp has no children!");
                }
                break;

            case "VarDecl":
                System.out.println("[DEBUG] 处理 VarDecl");

                for (ASTNode varDef : node.getChildren()) {
                    System.out.println("[DEBUG][VarDecl] 处理 VarDef 节点");
                    if (!"VarDef".equals(varDef.getType())) {
                        System.err.println("[ERROR][VarDecl] 非 VarDef 节点，跳过: " + varDef.getType());
                        continue; // 跳过非VarDef节点
                    }

                    ASTNode identNode = varDef.getChildren().get(0);
                    System.out.println("[DEBUG][VarDecl] 处理标识符节点");
                    varName = identNode.getValue();
                    System.out.println("[DEBUG][VarDecl] 变量名: " + varName);

                    // 检查变量是否已在 *当前作用域* 存在
                    if (symbolTableStack.isEmpty()) {
                         throw new RuntimeException("错误：尝试在没有局部作用域的情况下定义变量 '" + varName + "'");
                    }
                    Map<String, Symbol> currentScope = symbolTableStack.peek(); // 获取当前作用域
                    if (currentScope.containsKey(varName)) {
                        // 变量在当前作用域已定义，抛出错误或警告
                        System.err.println("[ERROR][VarDecl] 变量 '" + varName + "' 在当前作用域已定义！");
                        // 可以选择跳过或抛出异常
                        continue; 
                    } else {
                        // 变量在当前作用域未定义，注册新变量
                        // --- 修正 level 和 offset 计算 ---
                        int currentLevelVar;
                        int currentOffsetVar;
                        if (symbolTableStack.size() == 1) { // 只包含基础作用域，说明是全局变量
                            currentLevelVar = GLOBAL_LEVEL; // -1
                            currentOffsetVar = globalSymbolTable.size(); // 全局偏移量
                        } else { // 栈大小 > 1，说明是局部变量
                            // PCode 的层级通常从 0 开始代表第一个局部作用域
                            currentLevelVar = symbolTableStack.size() - 1; // 0 for func, 1 for block inside func, etc.
                            currentOffsetVar = currentScope.size(); // 当前局部作用域内的偏移量
                        }
                        // --- 结束修正 ---
                        
                        Symbol symNew = new Symbol(varName, "var", currentLevelVar); // 使用计算出的 level
                        symNew.offset = currentOffsetVar; // 使用计算出的 offset
                        registerSymbol(symNew); // registerSymbol 会根据 level 决定放入全局表还是栈顶 Map
                        System.out.println("[DEBUG][VarDecl] 注册新变量: " + varName + ", level=" + symNew.level + ", offset=" + symNew.offset);
                    }

                    // 获取符号信息（现在 getSymbol 会正确查找）
                    sym = getSymbol(varName); 
                    System.out.println("[DEBUG][VarDecl] 获取变量信息: " + varName + "，level=" + sym.level + "，offset=" + sym.offset);

                    // 如果包含初始化
                    if (varDef.getChildren().size() > 1) {
                        System.out.println("[DEBUG][VarDecl] 检测到初始化表达式，生成中间代码...");
                        ASTNode initValNode = varDef.getChildren().get(varDef.getChildren().size() - 1);
                        visit(initValNode); // visit InitVal / Exp / {...}
                        
                        // --- 使用修正后的偏移量计算逻辑生成 STO ---
                        finalOffset = sym.offset;
                        // VarDecl 定义的变量不是参数，isParam 应为 false，无需 +1
                        // if (sym.level != GLOBAL_LEVEL && sym.isParam) { 
                        //    finalOffset += 1;
                        // }
                        emit(new PCode(PCode.OpCode.STO, sym.level, finalOffset), varDef);
                        // --- 结束修正 ---
                        
                        System.out.println("[DEBUG][VarDecl] 生成 STO 指令，将值存入变量 " + varName);
                    }
                }
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
                Symbol symStore = getSymbol(name);
                System.out.println("[DEBUG] 生成 STO 指令: 存储到变量 " + name + " (地址 " + symStore.offset + ")");
                emit(new PCode(PCode.OpCode.STO, symStore.level, symStore.offset + (symStore.isParam ? 1 : 0)), node);
                // 赋值语句的值通常不留在栈上，STO 会消耗栈顶元素
                break;

            case "AssignExp":
                // 处理赋值表达式：先计算右值，然后存储到左值
                System.out.println("[DEBUG] 处理AssignExp节点 (For专用-赋值表达式-不吃分号版)");
                // 处理赋值表达式：step部分，比如 i = i + 1

                System.out.println("[DEBUG] 访问右值表达式...");
                visit(node.getChildren().get(1)); // 访问右值表达式（i+1）

                ASTNode lvalNode = node.getChildren().get(0);
                name = lvalNode.getChildren().get(0).getValue();
                sym = getSymbol(name);

                System.out.println("[DEBUG] 左值变量名: " + name + "，变量地址: " + sym.offset);

                System.out.println("[DEBUG] [AssignExp] 生成 STO 指令: " + name + " 地址 " + sym.offset);
                emit(new PCode(PCode.OpCode.STO, sym.level, sym.offset + (sym.isParam ? 1 : 0)), node);
                break;
            

            case "Exp":
                // 处理表达式
                for (ASTNode child : node.getChildren()) {
                    visit(child);
                }
                // ASTNode child = node.getChildren().get(0);
                // String childType = child.getType(); // 判断孩子节点的类型！！！

                // if (childType.equals("AddExp")) {
                //     visit(child);
                // } else if (childType.equals("RelExp")) {
                //     visit(child);
                // } else if (childType.equals("MulExp")) {
                //     visit(child);
                // } else if (childType.equals("UnaryExp")) {
                //     visit(child);
                // } else {
                //     System.err.println("x:[ERROR] Exp节点遇到未知子节点类型: " + childType);
                // }
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
            
            case "Getchar":
                System.out.println("[DEBUG] 生成 READ 指令 (getchar)");
                emit(new PCode(PCode.OpCode.READ, 0, 0), node);
                break;

            case "Printf":
                // 处理输出语句
                System.out.println("[DEBUG] 处理 Printf");

                // 判断第一个子节点是否是字符串常量
                ASTNode first = node.getChildren().get(0);
                if (first.getType().equals("STRCON")) {
                    String str = first.getValue();  // 获取STRCON值 比如 "21371295\n"
                    System.out.println("[DEBUG] 输出字符串常量: " + str);

                    // ✅ 直接进行转义处理
                    String parsed = parseEscapes(str);
                    System.out.println("[DEBUG] 格式化解析后字符串: " + parsed);

                    // ✅ 统计 format 占位符数量
                    int formatCount = 0;
                    for (int i = 0; i < parsed.length(); i++) {
                        if (parsed.charAt(i) == '%' && i + 1 < parsed.length()) {
                            char next = parsed.charAt(i + 1);
                            if (next == 'd' || next == 'c' || next == 's') {
                                formatCount++;
                                i++;
                            }
                        }
                    }

                    // ✅ 压入对应数量的参数表达式
                    for (int i = 1; i <= formatCount; i++) {
                        ASTNode argExp = node.getChildren().get(i);
                        visit(argExp); // ⚠️ 顺序和 format 一一对应
                    }

                    // 如果你支持字符串输出，建议加入 PRINTSTR 指令（可自定义）
                    int idx = getStringIndex(str);
                    emit(new PCode(PCode.OpCode.PRINTSTR, 0, idx), node); // 🎯生成PRINTSTR指令
                
                    // ✅ 提前 break，不再进入后面的 PRINT 循环
                    break;
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
                for (ASTNode childNode : node.getChildren()) {
                    if (childNode.getType().equals("IntLiteral")
                     || childNode.getType().equals("INTCON") 
                     || childNode.getType().equals("CharLiteral") 
                     || childNode.getType().equals("CHRCON") 
                    ) {
                        visit(childNode); // 访问 IntLiteral 或 INTCON
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

            case "CHRCON":
            case "CharLiteral":
                String charStr = node.getValue();
                if (charStr.length() >= 3 && charStr.startsWith("'") && charStr.endsWith("'")) {
                    char innerChar = charStr.charAt(1); // 提取中间的字符
                    int asciiVal = (int) innerChar;
                    System.out.println("[DEBUG] 生成 LIT 指令: 加载字符ASCII码 " + asciiVal);
                    emit(new PCode(PCode.OpCode.LIT, 0, asciiVal), node);
                } else {
                    System.err.println("[ERROR] 无法处理非法字符常量: " + charStr);
                    emit(new PCode(PCode.OpCode.LIT, 0, 0), node); // 出错时加载0
                }
                break;

            case "LVal":
                // LVal 在赋值语句左侧由 AssignStmt 处理 (获取地址)
                // LVal 在表达式右侧表示加载值
                System.out.println("[DEBUG] 处理 LVal (作为右值)");
                String varNameRVal = node.getChildren().get(0).getValue(); // LVal -> IDENFR
                System.out.println("[DEBUG] 加载变量: " + varNameRVal);
                sym = getSymbol(varNameRVal);
                System.out.println("[DEBUG] 生成 LOD 指令: 加载变量 " + varNameRVal + " (地址 " + sym.offset + ")");
                emit(new PCode(PCode.OpCode.LOD, sym.level, sym.offset), node);
                System.out.println("[DEBUG] LVal 处理完成");
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

                // // 🧩 2. 【新增】插入 STO 指令，把参数从栈存入函数作用域内存（bp + 0, bp + 1, ...）
                // for (int i = args.size() - 1; i >= 0; i--) {
                //     emit(new PCode(PCode.OpCode.STO, 0, i), node); // 从栈顶逆序存入
                // }
                
                // ✅ 3. CALL 跳转 获取函数入口地址
                Integer funcAddr = funcEntryMap.get(calledFuncName);
                if (funcAddr == null) {
                    System.err.println("[ERROR] 找不到函数 '" + calledFuncName + "' 的入口地址");
                    throw new RuntimeException("未定义的函数: " + calledFuncName);
                }
                
                // 生成 CALL 指令
                System.out.println("[DEBUG] 生成 CALL 指令，跳转到函数 '" + calledFuncName + "' 的入口地址: " + funcAddr);
                // emit(new PCode(PCode.OpCode.CALL, 0, funcAddr), node);
                emit(new PCode(PCode.OpCode.CALL, args.size(), funcAddr), node); // 调用时传递参数个数
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

            case "IfStmt":
                System.out.println("[DEBUG] 处理 IfStmt");

                // 获取IfStmt的子节点
                ASTNode condNode = node.getChildren().get(0); // 条件判断
                ASTNode thenNode = node.getChildren().get(1); // then分支
                ASTNode elseNode = (node.getChildren().size() > 2) ? node.getChildren().get(2) : null; // else分支（可能有可能没有）

                int elseLabel = labelCount++;
                int exitLabel = labelCount++;

                // 先生成条件判断
                visit(condNode);

                if (elseNode != null) {
                    // 有else分支

                    // 条件不满足跳转到else
                    emit(new PCode(PCode.OpCode.JPC, 0, elseLabel), node);

                    // then分支
                    visit(thenNode);

                    // 执行完then后直接跳出整个if-else结构
                    emit(new PCode(PCode.OpCode.JMP, 0, exitLabel), node);

                    // elseLabel的实际地址就是当前pc
                    // elseLabel 代表 "else分支开始"的位置
                    // 回填指令地址
                    // 真正生成时，很多指令的跳转目标，根本还没生成出来，只能先占位
                    // codeList.size()就是current pc
                    labelAddressMap.put(elseLabel, codeList.size());

                    // else分支
                    visit(elseNode);

                    // exitLabel 代表 "整个if-else结束"的位置。
                    labelAddressMap.put(exitLabel, codeList.size()); // if-else结束
                } else {
                    // 没有else分支

                    // 条件不满足跳到if外
                    emit(new PCode(PCode.OpCode.JPC, 0, exitLabel), node);

                    // then分支
                    visit(thenNode);

                    // exitLabel打标记（逻辑上，不是emit）
                    labelAddressMap.put(exitLabel, codeList.size()); // 让跳转地址在labelAddressMap中记录
                }

                break;

            case "ForStmt":
                System.out.println("[DEBUG] 处理 ForStmt");
            
                // for循环各部分
                ASTNode initNode = node.getChildren().get(0);  // 初始赋值
                System.out.println("[DEBUG][ForStmt] initNode 类型: " + initNode.getType());


                condNode = node.getChildren().get(1);  // 条件
                
                ASTNode stepNode = node.getChildren().get(2);  // 步进
                List<ASTNode> bodyNodes = node.getChildren().subList(3, node.getChildren().size()); // 循环体
            
                // 生成init部分
                if (initNode != null && !"Null".equals(initNode.getType())) {
                    System.out.println("[DEBUG] 生成For循环初始化部分，处理AssignStmt赋值...");
                    // visit(initNode);

                    lvalNode = initNode.getChildren().get(0);  // 左边 LVal
                    ASTNode expNode = initNode.getChildren().get(1);   // 右边 Exp
                    System.out.println("[DEBUG] [ForInit] 左边LVal节点: " + lvalNode.getType() + ", 右边Exp节点: " + expNode.getType());

                    visit(expNode); // 先visit右边，把值压栈！
                    System.out.println("[DEBUG] [ForInit] visit完右边表达式，值已压栈");

                    // 取左边的变量名（Lval的孩子是Ident节点）
                    ASTNode identNode = lvalNode.getChildren().get(0); // 这一步！！！！一定要先.get(0)，到IDENFR

                    String forVarName = lvalNode.getChildren().get(0).getValue();
                    Symbol forSym = getSymbol(forVarName);
                    System.out.println("[DEBUG] [ForInit] 左值变量名: " + lvalNode.getValue() + "，地址: " + forSym.offset);

                    emit(new PCode(PCode.OpCode.STO, forSym.level, forSym.offset + (forSym.isParam ? 1 : 0)), initNode);
                    System.out.println("[DEBUG][ForInit] 把初始化值存到地址 " + forSym.offset);
                }
            
                int condLabel = labelCount++;
                exitLabel = labelCount++;
                int stepLabel = labelCount++;

                exitLabelStack.push(exitLabel);
                stepLabelStack.push(stepLabel);
            
                // condLabel:
                System.out.println("[DEBUG] 记录循环条件condLabel = " + condLabel + ", 循环结束exitLabel = " + exitLabel);
                labelAddressMap.put(condLabel, codeList.size());

                // 生成cond条件判断
                if (condNode != null && !"Null".equals(condNode.getType())) {
                    System.out.println("[DEBUG] 生成 For循环 条件判断部分");
                    visit(condNode);
                    // 条件不满足，跳到exitLabel
                    emit(new PCode(PCode.OpCode.JPC, 0, exitLabel), node);
                } else {
                    System.out.println("[DEBUG] 条件为空，永不跳出 (死循环)");
                    // 注意，如果没有cond条件，要小心死循环
                    // 不加JPC，直接继续执行体
                }
            
                // 生成循环体 body
                System.out.println("[DEBUG] 生成循环体 body...");
                for (ASTNode stmt : bodyNodes) {
                    visit(stmt);
                }

                labelAddressMap.put(stepLabel, codeList.size());
            
                // 生成步进 step
                if (stepNode != null && !"Null".equals(stepNode.getType())) {
                    System.out.println("[DEBUG] 生成 For循环 步进部分");
                    visit(stepNode);
                }
            
                // 回到条件判断
                System.out.println("[DEBUG] 回跳到循环条件判断 condLabel...");
                emit(new PCode(PCode.OpCode.JMP, 0, condLabel), node);
            
                // exitLabel:
                System.out.println("[DEBUG] 设置exitLabel实际位置：PC=" + codeList.size());
                labelAddressMap.put(exitLabel, codeList.size());

                exitLabelStack.pop();
                stepLabelStack.pop();
            
                break;
            
            case "RelExp":
            case "RelExp_LSS":
            case "RelExp_LEQ":
            case "RelExp_GRE":
            case "RelExp_GEQ":
            case "RelExp_EQL":
            case "RelExp_NEQ":
                // RelExp: AddExp | RelExp ('<' | '>' | '<=' | '>=') AddExp
                System.out.println("[DEBUG] 处理 RelExp 类节点");

                if (node.getChildren().size() == 2) {
                    // ========================
                    // ✅ 处理 RelExp_GRE / RelExp_LSS 这种简化节点
                    // 节点只有两个孩子：左边AddExp，右边AddExp
                    // 操作符信息藏在节点类型名里（如 RelExp_GRE）
                    // ========================
                    
                    ASTNode left = node.getChildren().get(0); // 左表达式
                    ASTNode right = node.getChildren().get(1); // 右表达式

                    // 先生成左右子表达式的计算代码
                    visit(left);
                    visit(right);

                    // 截取节点名，比如从"RelExp_GRE"截出"GRE"
                    String relOp = node.getType().substring("RelExp_".length());

                    switch (relOp) {
                        case "LSS": // <
                            emit(new PCode(PCode.OpCode.LSS, 0, 0), node);
                            break;
                        case "LEQ": // <=
                            emit(new PCode(PCode.OpCode.LEQ, 0, 0), node);
                            break;
                        case "GRE": // >
                            emit(new PCode(PCode.OpCode.GTR, 0, 0), node);
                            break;
                        case "GEQ": // >=
                            emit(new PCode(PCode.OpCode.GEQ, 0, 0), node);
                            break;
                        case "EQL": // ==
                            emit(new PCode(PCode.OpCode.EQL, 0, 0), node);
                            break;
                        case "NEQ": // !=
                            emit(new PCode(PCode.OpCode.NEQ, 0, 0), node);
                            break;
                        default:
                            // 遇到未知操作符，输出错误信息
                            System.err.println("[ERROR] 不支持的RelExp操作符类型: " + node.getType());
                    }

                } else if (node.getChildren().size() == 3) {
                    // ========================
                    // ✅ 处理标准RelExp节点（带操作符子节点）
                    // 节点有三个孩子：左AddExp，右AddExp，中间是操作符（比如 "<"）
                    // 操作符信息存在opNode.getValue()里
                    // ========================

                    ASTNode left = node.getChildren().get(0); // 左表达式
                    ASTNode right = node.getChildren().get(1); // 右表达式
                    ASTNode opNode = node.getChildren().get(2); // 中间符号节点

                    // 先生成左右子表达式的计算代码
                    visit(left);
                    visit(right);

                    // 取出操作符，比如 "<"、">"、"=="等
                    String op = opNode.getValue();

                    switch (op) {
                        case "<":
                            emit(new PCode(PCode.OpCode.LSS, 0, 0), node);
                            break;
                        case ">":
                            emit(new PCode(PCode.OpCode.GTR, 0, 0), node);
                            break;
                        case "<=":
                            emit(new PCode(PCode.OpCode.LEQ, 0, 0), node);
                            break;
                        case ">=":
                            emit(new PCode(PCode.OpCode.GEQ, 0, 0), node);
                            break;
                        case "==":
                            emit(new PCode(PCode.OpCode.EQL, 0, 0), node);
                            break;
                        case "!=":
                            emit(new PCode(PCode.OpCode.NEQ, 0, 0), node);
                            break;
                        default:
                            System.err.println("[ERROR] 不支持的RelExp符号: " + op);
                    }
                } else {
                    // 出错保护：防止AST结构意外
                    System.err.println("[ERROR] RelExp 节点子节点数量异常: " + node.getChildren().size());
                }
                break;
            
            // 逻辑或 ||
            case "LOrExp":
                System.out.println("[DEBUG] 处理 LOrExp 节点");
            
                ASTNode LOrleft = node.getChildren().get(0); // 左表达式
                ASTNode LOrright = node.getChildren().get(1); // 右表达式
                visit(LOrleft); // 左边表达式
                visit(LOrright); // 右边表达式
            
                // 栈顶两个元素做逻辑或
                emit(new PCode(PCode.OpCode.OR, 0, 0), node);
                break;

            case "LAndExp":
                System.out.println("[DEBUG] 处理 LAndExp 节点");
            
                ASTNode LAndleft = node.getChildren().get(0); // 左表达式
                ASTNode LAndright = node.getChildren().get(1); // 右表达式
                visit(LAndleft); // 左边表达式
                visit(LAndright); // 右边表达式
            
                // 栈顶两个元素做逻辑与
                emit(new PCode(PCode.OpCode.AND, 0, 0), node);
                break;

                case "EqExp_EQL":
                System.out.println("[DEBUG] 处理 EqExp_EQL 节点 (==)");
            
                ASTNode Eqleft = node.getChildren().get(0); // 左表达式
                ASTNode Eqright = node.getChildren().get(1); // 右表达式
                visit(Eqleft); // 左边表达式
                visit(Eqright); // 右边表达式
            
                emit(new PCode(PCode.OpCode.EQL, 0, 0), node);
                break;
            
            case "EqExp_NEQ":
                System.out.println("[DEBUG] 处理 EqExp_NEQ 节点 (!=)");
            
                ASTNode NEQleft = node.getChildren().get(0); // 左表达式
                ASTNode NEQright = node.getChildren().get(1); // 右表达式
                visit(NEQleft); // 左边表达式
                visit(NEQright); // 右边表达式
            
                emit(new PCode(PCode.OpCode.NEQ, 0, 0), node);
                break;

            case "BreakStmt":
                if (exitLabelStack.isEmpty()) {
                    throw new RuntimeException("[ERROR] break不在循环内部使用！");
                }
                int breakTarget = exitLabelStack.peek();
                System.out.println("[DEBUG] 遇到break，跳转到 exitLabel: " + breakTarget);
                emit(new PCode(PCode.OpCode.JMP, 0, breakTarget), node);
                break;
            
            case "ContinueStmt":
                if (stepLabelStack.isEmpty()) {
                    throw new RuntimeException("[ERROR] continue不在循环内部使用！");
                }
                int continueTarget = stepLabelStack.peek();
                System.out.println("[DEBUG] 遇到continue，跳转到 stepLabel: " + continueTarget);
                emit(new PCode(PCode.OpCode.JMP, 0, continueTarget), node);
                break;

            case "ModExpr":
                System.out.println("[DEBUG] 处理 ModExpr 节点 (%)");
                visit(node.getChildren().get(0)); // 访问左操作数
                visit(node.getChildren().get(1)); // 访问右操作数
                emit(new PCode(PCode.OpCode.MOD, 0, 0), node); // 生成取模指令
                break;

            case "MINU":
                System.out.println("[DEBUG] 处理 MINU 节点 (取负号 -exp)");
                visit(node.getChildren().get(0)); // 先计算表达式的值
                emit(new PCode(PCode.OpCode.LIT, 0, 0), node); // 压入0
                emit(new PCode(PCode.OpCode.SWAP, 0, 0), node); // 交换栈顶
                emit(new PCode(PCode.OpCode.SUB, 0, 0), node); // 做 0 - val
                break;

            case "NOT":
                System.out.println("[DEBUG] 处理 NOT 节点 (逻辑非 !exp)");
                visit(node.getChildren().get(0)); // 先计算表达式的值
                emit(new PCode(PCode.OpCode.LIT, 0, 0), node); // 压入0
                emit(new PCode(PCode.OpCode.EQL, 0, 0), node); // 判断是否等于0，得到逻辑非
                break;

            case "GetCharStmt":
                System.out.println("[DEBUG] 生成 READ 指令 (getchar)");
                emit(new PCode(PCode.OpCode.READ, 0, 0), node);
                break;
            
            
            case "UnaryOp": // UnaryOp 节点通常只包含操作符 Token
                // 不直接生成代码，由 UnaryExp 处理
                break;

            default:
                System.out.println("⚠️ CodeGenerator: 未处理的节点类型: " + node.getType() + "，尝试访问子节点...");
                // 兜底策略：尝试访问子节点，可能适用于某些容器型节点
                for (ASTNode childnNode : node.getChildren()) {
                    visit(childnNode);
                }
        }
    }

    private void patchLabels() {
        for (int i = 0; i < codeList.size(); i++) {
            PCode inst = codeList.get(i);
            if (inst.getOp() == PCode.OpCode.JMP || inst.getOp() == PCode.OpCode.JPC) {
                int labelId = inst.getAddress();
                Integer realPc = labelAddressMap.get(labelId);
                if (realPc == null) {
                    throw new RuntimeException("[ERROR] Label回填失败: 找不到 label " + labelId);
                }
                System.out.println("[回填] 将第" + i + "条指令的跳转地址 " + labelId + " -> 实际PC " + realPc);
                inst.setAddress(realPc);
            }
        }
    }
    

    // private int getVarAddress(String varName) {
    //     if (varName == null || varName.isEmpty()) {
    //         System.err.println("[ERROR] CodeGenerator: 无效的变量名!");
    //         return -1; // 返回无效地址
    //     }

    //     // 如果变量已经有地址，直接返回
    //     if (varAddressMap.containsKey(varName)) {
    //         return varAddressMap.get(varName);
    //     }

    //     // 为新变量分配地址
    //     int address = nextVarAddress++;
    //     varAddressMap.put(varName, address);

    //     // ✅ 同时构建 Symbol 对象（默认 level 为 0，可以之后调整）
    //     Symbol sym = new Symbol(varName, "int", 0);
    //     sym.offset = address;
    //     symbolInfoMap.put(varName, sym);

    //     System.out.println("[DEBUG] 为变量 '" + varName + "' 分配新地址: " + address);
    //     return address;
    // }   


    // 这俩代码用来简化的（还没用）
    private void emitLOD(String name, ASTNode node) {
        Symbol sym = getSymbol(name);
        emit(new PCode(PCode.OpCode.LOD, sym.level, sym.offset + (sym.isParam ? 1 : 0)), node);
    }
    private void emitSTO(String name, ASTNode node) {
        Symbol sym = getSymbol(name);
        emit(new PCode(PCode.OpCode.STO, sym.level, sym.offset + (sym.isParam ? 1 : 0)), node);
    }
    

}
