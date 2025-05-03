package frontend;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

public class Parser {
    private CodeGenerator codeGenerator;
    private List<Token> tokens; // 词法单元列表
    private int index = 0; // 当前解析位置
    private Token currentToken; // 当前词法单元
    private Token previousToken; // 上一个词法单元
    public List<Error> errors; // 错误列表
    private boolean outputEnabled = true; // 是否输出词法单元和语法成分
    private Set<Integer> errorLines;
    private Scope currentScope; // 当前作用域
    private int scopeCounter = 1; // 作用域计数器，初始为1（全局作用域序号为1）
    public List<Symbol> symbolList = new ArrayList<>(); // 用于输出的符号列表
    private boolean isError = false; // 是否存在错误，用于控制是否输出符号表
    private String currentBType = ""; // 用于保存当前的基本类型
    private String currentFuncType = ""; // 当前函数的返回类型
    private int loopDepth = 0; // 当前循环嵌套深度
    private int lastBlockEndLineNumber = -1;
    private boolean hasSyntaxErrorInCurrentFunc = false; // 新增：当前函数体内是否存在语法错误
    private ASTNode root = new ASTNode("Program");

    // 移除 globalOffset 和 localOffset，这些应由 CodeGenerator 管理
    // private int globalOffset = 0; // ✅ 全局变量地址偏移
    // private int localOffset = 0;  // ✅ 函数内变量地址偏移

    public Parser(List<Token> tokens, List<Error> errors, Set<Integer> errorLines,CodeGenerator codeGenerator) {
        this.tokens = tokens;
        this.errors = errors;
        this.errorLines = errorLines; // 使用共享的 errorLines 集合
        this.codeGenerator = codeGenerator;
        this.currentScope = new Scope(null, scopeCounter); // 初始化全局作用域
        if (!tokens.isEmpty()) {
            currentToken = tokens.get(index);
        }
        this.outputEnabled = false; // 关闭输出
    }

    private void enterScope() {
        scopeCounter++;
        currentScope = new Scope(currentScope, scopeCounter);
    }

    private void exitScope() {
        // 在退出作用域时，将当前作用域中的符号添加到 symbolList 中
        symbolList.addAll(currentScope.getSymbols());
        currentScope = currentScope.parentScope;
    }

    // 获取下一个词法单元
    private void nextToken() {
        previousToken = currentToken;
        index++;
        while (index < tokens.size()) {
            currentToken = tokens.get(index);
            if (currentToken.type != TokenType.ERROR) {
                break;
            } else {
                // 已经在词法分析器中记录了错误，这里直接跳过
                index++;
            }
        }
        if (index >= tokens.size()) {
            currentToken = null;
        }
    }

    // 匹配指定的词法单元类型
    private boolean match(TokenType type) {
        while (currentToken != null && currentToken.type == TokenType.ERROR) {
            nextToken();
        }
        if (currentToken != null && currentToken.type == type) {
            if (outputEnabled) {
                System.out.println(currentToken.toString());
            }
            previousToken = currentToken; // 在这里更新 previousToken
            nextToken();
            return true;
        } else {
            return false;
        }
    }


    // 移除不带行号参数的 reportError 方法

    // 报告错误，使用指定的行号
    private void reportError(char errorType, int lineNumber) {
        if (!errorLines.contains(lineNumber)) {
            errors.add(new Error(lineNumber, errorType));
            errorLines.add(lineNumber);
        }
        if (errorType == 'k' || errorType == 'j' || errorType == 'i') {
            hasSyntaxErrorInCurrentFunc = true; // 设置标志
        }
    }


    // 报告错误，使用当前或前一个词法单元的行号
    private void reportError(char errorType) {
        int errorLineNumber = previousToken != null ? previousToken.lineNumber :
                (currentToken != null ? currentToken.lineNumber : 1);
        if (!errorLines.contains(errorLineNumber)) {
            errors.add(new Error(errorLineNumber, errorType));
            errorLines.add(errorLineNumber);
        }
    }


    // 解析程序入口
    public ASTNode parse() {
        CompUnit();
        // 收集全局作用域的符号
        symbolList.addAll(currentScope.getSymbols());
        System.out.println("🚧 Program 子节点数量: " + root.getChildren().size());
        return root;
    }

    // CompUnit → {Decl} {FuncDef} MainFuncDef
    private void CompUnit() {
        while (isDecl()) {
            ASTNode node = Decl(); // 会自动 add 到 root 上（VarDecl/ConstDecl 会挂到 root）
            if (node != null) {
                root.addChild(node); // ✅ 现在由你统一挂载
            }
        }
    
        // 🧠 添加一个新的 list 来暂存 FuncDef
        List<ASTNode> funcDefNodes = new ArrayList<>();
        while (isFuncDef()) {
            ASTNode node = FuncDef(); // ⛳️ 你要让 FuncDef() 返回 ASTNode
            funcDefNodes.add(node);   // 📥 暂存所有函数定义
        }
    
        ASTNode main = MainFuncDef(); // 🧠 main 最后挂！
    
        for (ASTNode func : funcDefNodes) {
            root.addChild(func); // ✅ 添加普通函数
        }
        if (main != null) {
            root.addChild(main);  // ✅ 只挂一次 main，主函数放最后
        }
    
        if (outputEnabled) {
            System.out.println("<CompUnit>");
        }
    }
    

    // 判断是否是声明
    private boolean isDecl() {
        return isConstDecl() || isVarDecl();
    }

    // Decl → ConstDecl | VarDecl
    private ASTNode Decl() {
        if (isConstDecl()) {
            return ConstDecl();
        } else if (isVarDecl()) {
            return VarDecl();
        } else {
            // 不可能到这里，报告错误
            reportError('k');
            return null;
        }
    }

    // 判断是否是常量声明
    private boolean isConstDecl() {
        return currentToken != null && currentToken.type == TokenType.CONSTTK;
    }

    // ConstDecl → 'const' BType ConstDef { ',' ConstDef } ';' // i
    private ASTNode ConstDecl() {
        ASTNode constDeclNode = new ASTNode("ConstDecl");

        if (!match(TokenType.CONSTTK)) {
            reportError('k');
            return constDeclNode; // 返回空节点避免null
        }
        BType(); // 会设置 currentBType（"int"/"char"）

        ASTNode firstDef = ConstDef(); // 第一个 ConstDef
        if (firstDef != null) {
            constDeclNode.addChild(firstDef);
        }

        while (match(TokenType.COMMA)) {
            ASTNode nextDef = ConstDef();
            if (nextDef != null) {
                constDeclNode.addChild(nextDef);
            }
        }

        if (!match(TokenType.SEMICN)) {
            int errorLineNumber = previousToken != null ? previousToken.lineNumber : 1;
            reportError('i', errorLineNumber);
        }
        // 输出 <ConstDecl>
        if (outputEnabled) {
            System.out.println("<ConstDecl>");
        }
        System.out.println("🚧 ConstDecl 子节点数量: " + constDeclNode.getChildren().size());
        return constDeclNode;
    }

    // 判断是否是变量声明
    private boolean isVarDecl() {
        if (currentToken != null && (currentToken.type == TokenType.INTTK || currentToken.type == TokenType.CHARTK)) {
            // 需要区分 VarDecl 和 FuncDef
            int tempIndex = index;
            Token tempToken = currentToken;

            nextToken(); // 移动到 Ident
            if (currentToken != null && currentToken.type == TokenType.IDENFR) {
                nextToken();
                if (currentToken != null && currentToken.type == TokenType.LPARENT) {
                    // 是函数定义
                    // 恢复状态
                    index = tempIndex;
                    currentToken = tempToken;
                    return false;
                } else {
                    // 是变量声明
                    // 恢复状态
                    index = tempIndex;
                    currentToken = tempToken;
                    return true;
                }
            } else {
                // 恢复状态
                index = tempIndex;
                currentToken = tempToken;
                return false;
            }
        }
        return false;
    }

    // VarDecl → BType VarDef { ',' VarDef } ';' // i
    private ASTNode VarDecl() {
        ASTNode varDeclNode = new ASTNode("VarDecl"); // 🌟 构造 VarDecl 节点
    
        BType();
        ASTNode firstDef = VarDef(); // ✅ 修改为返回 VarDef 节点
        varDeclNode.addChild(firstDef); // ✅ 添加第一个VarDef
    
        while (match(TokenType.COMMA)) {
            ASTNode moreDef = VarDef();
            varDeclNode.addChild(moreDef);
        }
    
        if (!match(TokenType.SEMICN)) {
            int errorLineNumber = previousToken != null ? previousToken.lineNumber : 1;
            reportError('i', errorLineNumber);
        }

        if (outputEnabled) {
            System.out.println("<VarDecl>");
        }
        return varDeclNode;
    }
    

    // BType → 'int' | 'char'
    private void BType() {
        if (currentToken != null && (currentToken.type == TokenType.INTTK || currentToken.type == TokenType.CHARTK)) {
            currentBType = currentToken.value; // 更新 currentBType
            match(currentToken.type);
        } else {
            // 不报告错误，返回以便上层处理
        }
    }


    // ConstExp → AddExp 注：使用的 Ident 必须是常量
    private ASTNode ConstExp() {
        ASTNode constExpNode = new ASTNode("ConstExp");
    
        ASTNode addExpNode = AddExp();
        if (addExpNode != null) {
            constExpNode.addChild(addExpNode); // 👈 AddExp 挂上
        }
    
        if (outputEnabled) {
            System.out.println("<ConstExp>");
        }
    
        return constExpNode;
    }
    
    // VarDef → Ident [ '[' ConstExp ']' ] | Ident [ '[' ConstExp ']' ] '=' InitVal // k
    private ASTNode VarDef() {
        ASTNode varDefNode = new ASTNode("VarDef"); // 🌟新增，构造 VarDef 节点
        Token identToken = currentToken;
    
        if (!match(TokenType.IDENFR)) {
            reportError('k');
            return varDefNode;
        }
    
        ASTNode identNode = new ASTNode(identToken); // 把 Ident 也挂进去
        varDefNode.addChild(identNode); // ✅ 将 Ident 节点挂上去
    
        if (match(TokenType.ASSIGN)) {
            // 处理 InitVal → Exp
            ASTNode initValNode = new ASTNode("InitVal"); // 包装为 InitVal 节点
            ASTNode expNode = Exp(); // 解析右边表达式
            initValNode.addChild(expNode); // InitVal → Exp
            varDefNode.addChild(initValNode); // VarDef → Ident = InitVal
        }
    
        String typeName = currentBType.equals("int") ? "Int" : "Char";
        Symbol symbol = new Symbol(identToken.value, typeName, currentScope.getScopeLevel());
        if (currentScope.getScopeLevel() == 1) { // 🚨 全局变量标记 level = -1
            symbol.level = -1; // ✅ 全局变量，level=-1 表示在 PCode 中为 globalBase
        }

        // 移除偏移量计算和对 CodeGenerator 的注册调用，这些由 CodeGenerator 在遍历 AST 时处理
        // if (symbol.level == -1) {
        //     symbol.offset = globalOffset++;
        // } else {
        //     symbol.offset = localOffset++;
        // }
        // codeGenerator.registerSymbol(symbol); // 移除

        // 保留 Parser 级别的作用域检查
        if (!currentScope.declare(symbol)) {
            reportError('b', identToken.lineNumber);
        }
        System.out.println("VarDef: " + identToken.value + " declared in scope " + currentScope.getScopeLevel());

    
        if (outputEnabled) {
            System.out.println("<VarDef>");
        }

        return varDefNode; // ✅ 返回这个节点
    }
    


    // ConstDef → Ident [ '[' ConstExp ']' ] '=' ConstInitVal // k
    private ASTNode ConstDef() {
        ASTNode constDefNode = new ASTNode("ConstDef");

        Token identToken = currentToken;
        if (!match(TokenType.IDENFR)) {
            reportError('k');
            return constDefNode;
        }

        // 添加 Ident 节点
        ASTNode identNode = new ASTNode(identToken);
        constDefNode.addChild(identNode);
        
        String typeName = ""; // 类型名称

        // 如果是数组
        if (match(TokenType.LBRACK)) {
            ASTNode lbrackNode = new ASTNode("LBRACK");
            constDefNode.addChild(lbrackNode);

            ASTNode constExpNode = ConstExp();

            if (!match(TokenType.RBRACK)) {
                reportError('k');
            } else {
                ASTNode rbrackNode = new ASTNode("RBRACK");
                constDefNode.addChild(rbrackNode);
            }

            typeName = currentBType.equals("int") ? "ConstIntArray" : "ConstCharArray";
        } else {
            typeName = currentBType.equals("int") ? "ConstInt" : "ConstChar";
        }

        // 等号赋值
        if (!match(TokenType.ASSIGN)) {
            reportError('k');
            return constDefNode;
        } else {
            ASTNode assignNode = new ASTNode("ASSIGN");
            constDefNode.addChild(assignNode);

            ASTNode initValNode = ConstInitVal(); // 解析 ConstInitVal
            if (initValNode!= null) {
                constDefNode.addChild(initValNode); // 挂到 ConstDef 上
            } else {
                // 如果 ConstInitVal 返回 null，可能表示解析失败，也应考虑报错
                reportError('k'); // 或其他错误码
            }
        }

        // ASTNode initValNode = ConstInitVal();
        // if (initValNode != null) {
        //     constDefNode.addChild(initValNode);
        // }

        // 检查符号重定义
        Symbol symbol = new Symbol(identToken.value, typeName, currentScope.getScopeLevel());
        if (currentScope.getScopeLevel() == 1) { // 🚨 全局作用域
            symbol.level = -1; // ✅ 全局变量，level=-1 表示在 PCode 中为 globalBase
        }

        // 移除偏移量计算和对 CodeGenerator 的注册调用，这些由 CodeGenerator 在遍历 AST 时处理
        // if (symbol.level == -1) {
        //     symbol.offset = globalOffset++;
        // } else {
        //     symbol.offset = localOffset++;
        // }
        // codeGenerator.registerSymbol(symbol); // 移除

        // 保留 Parser 级别的作用域检查
        if (!currentScope.declare(symbol)) {
            reportError('b', identToken.lineNumber);
        }
        System.out.println("ConstDef: " + identToken.value + " declared in scope " + currentScope.getScopeLevel());
        
        if (outputEnabled) {
            System.out.println("<ConstDef>");
        }

        return constDefNode;
    }


    // ConstInitVal → ConstExp | '{' [ ConstExp { ',' ConstExp } ] '}' | StringConst
    private ASTNode ConstInitVal() {
        ASTNode initValNode = new ASTNode("ConstInitVal");
    
        if (match(TokenType.LBRACE)) {
            initValNode.addChild(new ASTNode("LBRACE"));
    
            if (!check(TokenType.RBRACE)) {
                ASTNode firstExp = ConstExp();
                if (firstExp != null) {
                    initValNode.addChild(firstExp);
                }
    
                while (match(TokenType.COMMA)) {
                    initValNode.addChild(new ASTNode("COMMA"));
                    ASTNode moreExp = ConstExp();
                    if (moreExp != null) {
                        initValNode.addChild(moreExp);
                    }
                }
    
                if (!match(TokenType.RBRACE)) {
                    reportError('k');
                } else {
                    initValNode.addChild(new ASTNode("RBRACE"));
                }
            } else {
                // 空初始化 {}
                match(TokenType.RBRACE);
                initValNode.addChild(new ASTNode("RBRACE"));
            }
        }
        else if (currentToken != null && currentToken.type == TokenType.STRCON) {
            Token strToken = currentToken;
            match(TokenType.STRCON);
            ASTNode strNode = new ASTNode(strToken);
            initValNode.addChild(strNode);
        }
        else {
            ASTNode constExpNode = ConstExp();
            if (constExpNode != null) {
                initValNode.addChild(constExpNode);
            }
        }
    
        if (outputEnabled) {
            System.out.println("<ConstInitVal>");
        }
    
        return initValNode;
    }
    

    // InitVal → Exp | '{' [ Exp { ',' Exp } ] '}' | StringConst
    private void InitVal() {
        if (match(TokenType.LBRACE)) {
            if (!match(TokenType.RBRACE)) {
                Exp();
                while (match(TokenType.COMMA)) {
                    Exp();
                }
                if (!match(TokenType.RBRACE)) {
                    reportError('k');
                }
            }
        } else if (currentToken != null && currentToken.type == TokenType.STRCON) {
            match(TokenType.STRCON);
        } else {
            Exp();
        }
        // 输出 <InitVal>
        if (outputEnabled) {
            System.out.println("<InitVal>");
        }
    }

    // 判断是否是函数定义
    private boolean isFuncDef() {
        if (currentToken != null && (currentToken.type == TokenType.VOIDTK || currentToken.type == TokenType.INTTK || currentToken.type == TokenType.CHARTK)) {
            int tempIndex = index;
            Token tempToken = currentToken;

            nextToken(); // 移动到 Ident
            if (currentToken != null && currentToken.type == TokenType.IDENFR) {
                nextToken();
                if (currentToken != null && currentToken.type == TokenType.LPARENT) {
                    // 是函数定义
                    // 恢复状态
                    index = tempIndex;
                    currentToken = tempToken;
                    return true;
                } else {
                    // 恢复状态
                    index = tempIndex;
                    currentToken = tempToken;
                    return false;
                }
            } else {
                // 恢复状态
                index = tempIndex;
                currentToken = tempToken;
                return false;
            }
        }
        return false;
    }

    // 判断是否是函数形参列表的开始
    private boolean isFuncFParamsStart() {
        return currentToken != null && (currentToken.type == TokenType.INTTK || currentToken.type == TokenType.CHARTK);
    }

    // FuncDef → FuncType Ident '(' [Fu ncFParams] ')' Block // j
    private ASTNode FuncDef() {
        hasSyntaxErrorInCurrentFunc = false;
        System.out.println("[DEBUG][FuncDef] 开始解析一个函数定义...");
        // 🔄 重置当前函数是否有语法错误的标志位，每次进入新的函数定义都得初始化
    
        TokenType funcType = currentToken.type;
        String funcTypeName = getFuncTypeName(funcType);
        currentFuncType = funcTypeName;
        System.out.println("[DEBUG][FuncDef] 函数返回类型解析为: " + funcTypeName);
        // 🔍 获取函数返回类型（int/void/char），并存储为当前函数的返回类型（供 return 语句检查使用）
    
        FuncType(); // 吃掉 int/void/char
        System.out.println("[DEBUG][FuncDef] 返回类型Token已吃掉, 当前Token: " + (currentToken != null ? currentToken.value : "null"));
        // 🧹 吃掉函数类型的 Token，移到下一个 Token
    
        if (!match(TokenType.IDENFR)) {
            System.out.println("[DEBUG][FuncDef] 缺少函数名，直接返回null");
            return null;
        }
        // ❌ 如果没有函数名，直接返回。虽然这里没报错，但返回后语义分析必然报错
        Token funcNameToken = previousToken;
        System.out.println("[DEBUG][FuncDef] 解析函数名: " + funcNameToken.value);
        // 📝 获取函数名标识符（match 成功之后 currentToken 会变，所以要用 previousToken）
    
        ASTNode funcNode = new ASTNode("FuncDef");
        funcNode.setSource("Parser.FuncDef() @ line " + funcNameToken.lineNumber);  // ✅ 添加来源信息
        // 🧱 构建函数定义的 AST 根节点，表示这是一个完整的函数定义结构

        // funcNode.addChild(new ASTNode(funcType)); // 类型作为子节点
        // ❌ 注释掉：之前错误地尝试用字符串构建 ASTNode，会编译失败

        funcNode.addChild(new ASTNode(funcNameToken)); // 函数名
        // ✅ 将函数名作为子节点添加到 AST 中，后续中间代码生成需要知道调用哪个函数
    
        Symbol funcSymbol = new Symbol(funcNameToken.value, funcTypeName, currentScope.getScopeLevel());
        // 🧭 构造符号对象，用于存入符号表，包含函数名、类型、所在作用域层级
        if (!currentScope.declare(funcSymbol)) {
            reportError('b', funcNameToken.lineNumber);
            System.out.println("[DEBUG][FuncDef] 函数名重定义错误： " + funcNameToken.value);
        } else {
            System.out.println("[DEBUG][FuncDef] 成功注册函数符号到当前作用域: " + funcNameToken.value);
        }    
        // 🚨 如果该作用域内已经定义了同名函数，报告重定义错误 'b'
    
        if (!match(TokenType.LPARENT)) {
            reportError('j', funcNameToken.lineNumber); // 🧩 函数名后面必须跟左括号 ( 否则就是语法错误 'j'
            System.out.println("[DEBUG][FuncDef] 缺少 ( ，返回函数节点");
            return funcNode; // 即使出错也返回节点，保持 AST 完整性 
        }
    
        ASTNode paramListNode = new ASTNode("FuncFParams"); // 即使空也加进去
        System.out.println("[DEBUG][FuncDef] 创建形参列表节点 FuncFParams");
        
        // 🧶 不管有没有参数，都先建一个参数列表节点，方便统一结构处理
        enterScope(); // 🚪 进入函数体作用域，参数变量应该注册在函数内部作用域中
        int localOffset = 0; // ✅ 每个函数体 offset 从 0 开始
        System.out.println("[DEBUG][FuncDef] 进入函数体作用域，localOffset 重置为 0");
        System.out.println("[DEBUG][FuncDef] 进入新的函数作用域，scope id = " + currentScope.getScopeLevel());
    
        if (match(TokenType.RPARENT)) {
            // 空参数
            // ✅ 空参数函数，直接吃掉右括号，啥都不做
            System.out.println("[DEBUG][FuncDef] 该函数是空参数函数");
        } else if (isFuncFParamsStart()) {
            FuncFParams(funcSymbol, paramListNode); // 你原本是处理符号，不构造AST
            // ⚠️ 这里也可以再构造 paramListNode 并填参数节点
            // 🧠 解析参数列表，并添加到符号表 funcSymbol.paramTypes 中
            if (!match(TokenType.RPARENT)) {
                reportError('j', funcNameToken.lineNumber);
                System.out.println("[DEBUG][FuncDef] 形参列表后缺少右括号");
            } else { // 🚨 参数列表后缺少右括号，报错类型 'j'
                System.out.println("[DEBUG][FuncDef] 形参列表解析完毕并正确闭合 )");
            }
        } else {
            reportError('j', funcNameToken.lineNumber); // ❌ 函数名后既不是 ) 也不是参数开头，那说明是错的
            System.out.println("[DEBUG][FuncDef] 既没有右括号也没有形参列表开头，非法语法");
        }
    
        funcNode.addChild(paramListNode); // 把参数节点挂上 // ✅ 即使参数为空，也加入 AST，保持结构一致性
        System.out.println("[DEBUG][FuncDef] 将 FuncFParams 节点挂到函数节点");

        ASTNode blockNode = Block(true); // 🧱 解析函数体（Block），true 表示这是函数块，用于 return 检查等
        funcNode.addChild(blockNode); // ✅ 将整个函数体加入 AST
        System.out.println("[DEBUG][FuncDef] 函数体Block解析完成并挂载");

        System.out.println("🧱 构造 FuncDef 节点：" + funcNameToken.value +
                   "，对象ID: " + System.identityHashCode(funcNode));
        // root.addChild(funcNode); // ✅ 挂到AST根上 // 🌳 **核心！** 把当前函数挂到 AST 根节点上（Program），否则中间代码生成访问不到！
        if (root.getChildren().contains(funcNode)) {
            System.out.println("🚨 [重复添加] 该函数 ASTNode 已经在 root 中了！funcName: " + funcNameToken.value);
        }else{
            System.out.println("🌳 将函数 " + funcNameToken.value +
                   " 挂载到 root AST，当前 root 子节点数: " + root.getChildren().size());
        }
        
        exitScope(); // 🚪 退出函数作用域，函数体中的局部变量生命周期结束
        System.out.println("[DEBUG][FuncDef] 退出函数作用域，回到上一级Scope");
    
        if (outputEnabled) {
            System.out.println("<FuncDef>"); // 📤 输出语法成分标签，适用于调试和输出语法分析过程
        }

        System.out.println("[DEBUG][FuncDef] 完成函数定义节点的构建，返回FuncNode");
        return funcNode; // ✅ 改成返回构建好的 ASTNode
    }
    


    private String getFuncTypeName(TokenType funcType) {
        switch (funcType) {
            case INTTK:
                return "IntFunc";
            case CHARTK:
                return "CharFunc";
            case VOIDTK:
                return "VoidFunc";
            default:
                return "unknownFunc";
        }
    }


    // FuncType → 'void' | 'int' | 'char'
    private void FuncType() {
        if (currentToken != null && (currentToken.type == TokenType.VOIDTK || currentToken.type == TokenType.INTTK || currentToken.type == TokenType.CHARTK)) {
            match(currentToken.type);
            if (outputEnabled) {
                System.out.println("<FuncType>");
            }
        } else {
            reportError('k');
        }
    }

    // FuncFParams → FuncFParam { ',' FuncFParam }
    private void FuncFParams(Symbol funcSymbol, ASTNode paramListNode) {
        System.out.println("[DEBUG][FuncFParams] 开始解析形参列表");

        ASTNode firstParam = FuncFParam(funcSymbol);
        paramListNode.addChild(firstParam);
        System.out.println("[DEBUG][FuncFParams] 解析了第一个形参");

        while (match(TokenType.COMMA)) {
            System.out.println("[DEBUG][FuncFParams] 发现逗号 , 继续解析下一个形参");
            ASTNode nextParam = FuncFParam(funcSymbol);
            paramListNode.addChild(nextParam);
            System.out.println("[DEBUG][FuncFParams] 解析了一个形参");
        }
        // 输出 <FuncFParams>
        if (outputEnabled) {
            System.out.println("[DEBUG][FuncFParams] 完成整个形参列表解析，输出 <FuncFParams>");
            System.out.println("<FuncFParams>");
        }
    }

    // FuncFParam → BType Ident ['[' ']'] // k
    private ASTNode FuncFParam(Symbol funcSymbol) {
        System.out.println("[DEBUG][FuncFParam] 开始解析单个形参");

        ASTNode paramNode = new ASTNode("FuncFParam"); // 🌟新建一个FuncFParam节点

        if (currentToken != null && (currentToken.type == TokenType.INTTK || currentToken.type == TokenType.CHARTK)) {
            String bType = currentToken.value;
            System.out.println("[DEBUG][FuncFParam] 形参基础类型识别为: " + bType);

            BType();
            
            Token identToken = currentToken;
            if (!match(TokenType.IDENFR)) {
                // 错误处理
                System.out.println("[DEBUG][FuncFParam] 缺少形参标识符 IDENFR，提前返回");
                return paramNode;
            }
            System.out.println("[DEBUG][FuncFParam] 识别形参名称: " + identToken.value);

            String typeName = "";
            if (match(TokenType.LBRACK)) {
                System.out.println("[DEBUG][FuncFParam] 识别到 '[' ，形参为数组类型");
                if (!match(TokenType.RBRACK)) {
                    reportError('k');
                    System.out.println("[DEBUG][FuncFParam] 缺少 ']'，报错");
                }
                typeName = bType.equals("int") ? "IntArray" : "CharArray";
            } else {
                typeName = bType.equals("int") ? "Int" : "Char";
            }

            System.out.println("[DEBUG][FuncFParam] 形参完整类型为: " + typeName);

            // 将参数类型添加到函数符号的 paramTypes 中
            funcSymbol.paramTypes.add(typeName);
            System.out.println("[DEBUG][FuncFParam] 已将形参类型加入函数符号 paramTypes 列表");

            // 检查符号重定义
            if (!currentScope.declare(new Symbol(identToken.value, typeName, currentScope.getScopeLevel()))) {
                reportError('b', identToken.lineNumber);
            }

             // 🌟🌟把识别到的ident挂到paramNode上
            paramNode.addChild(new ASTNode(identToken));
            System.out.println("[DEBUG][FuncFParam] 将形参 " + identToken.value + " 挂载到FuncFParam节点");
            
            if (outputEnabled) {
                System.out.println("[DEBUG][FuncFParam] 加入形参: 名字=" + identToken.value + ", 类型=" + typeName);
                System.out.println("<FuncFParam>");
            }
        } else {
            // 错误处理
            System.out.println("[DEBUG][FuncFParam] 当前token不是形参起始符号 (int/char)，跳过处理");
        }

        return paramNode; // 🔥🔥返回新建的FuncFParam节点
    }


    // MainFuncDef → 'int' 'main' '(' ')' Block // j
    private ASTNode MainFuncDef() {
        hasSyntaxErrorInCurrentFunc = false; // 🔄 重置当前函数语法错误标志
        if (match(TokenType.INTTK) && match(TokenType.MAINTK)) {
            currentFuncType = "IntFunc"; // 🧠 主函数返回类型固定为 int
            Token funcNameToken = previousToken;
            if (match(TokenType.LPARENT)) {
                if (!match(TokenType.RPARENT)) {
                    reportError('j', funcNameToken.lineNumber);
                }
                enterScope(); // 🚪 主函数体作为一个新的作用域
                int localOffset = 0; // ✅ 每个函数体 offset 从 0 开始
                System.out.println("[DEBUG][MainFuncDef] 进入主函数作用域，localOffset 重置为 0");

                ASTNode mainNode = new ASTNode("MainFuncDef");
                mainNode.setSource("Parser.MainFuncDef() @ line " + currentToken.lineNumber);

                ASTNode blockNode = Block(true);  // 🧱 传 true 表示是函数块
                mainNode.addChild(blockNode);     // ✅ 挂上 Block 子节点
                
                // root.addChild(mainNode);          // 将 MainFuncDef 节点添加到根节点
                exitScope(); // 🧼 退出作用域

                if (outputEnabled) {
                    System.out.println("<MainFuncDef>");
                }
                return mainNode; // ✅ 返回构建好的主函数 AST 节点
            } else {
                reportError('j', funcNameToken.lineNumber); // 🚨 缺失左括号
            }
        } else {
            reportError('j', currentToken != null ? currentToken.lineNumber : 1); // 🚨 缺失 main 函数头部
        }
        return null; // ❌ 若发生语法错误，返回 null
    }  


    // Block → '{' { BlockItem } '}'
    private ASTNode Block(boolean isFuncBlock) {
        ASTNode blockNode = new ASTNode("Block");
        if (match(TokenType.LBRACE)) {
            enterScope();  // ✅ 开启新作用域！

            while (isBlockItem()) {
                ASTNode itemNode = BlockItem();
                if (itemNode != null) {
                    blockNode.addChild(itemNode);
                }
            }
            exitScope();  // ✅ 退出作用域！

            if (!match(TokenType.RBRACE)) {
                reportError('j');
            }
            if (outputEnabled) {
                System.out.println("<Block>");
            }
        }
        return blockNode;
    }

    private ASTNode BlockItem() {
        if (isDecl()) {
            ASTNode declNode = Decl();
            return declNode;  // 声明不需要生成中间代码
        } else if (isStmt()) {
            return Stmt();  // 返回语句节点
        }
        return null;
    }

    // Stmt就是“语句”,比如 表达式语句（比如 a = b + 1;）,赋值语句（x = 3;）,控制语句（if/while/return/...）,块语句（{ ... }）
    // 负责判断语句种类，并生成对应语法树节点。
    private ASTNode Stmt() {
        ASTNode stmtNode = new ASTNode("Stmt");
        
        if (currentToken == null) {
            return stmtNode;  // 如果没有更多的 token，直接返回空的语句节点
        }

        System.out.println("[DEBUG][Stmt] 当前Token: " + currentToken.type + "，值: " + currentToken.value + "，行: " + currentToken.lineNumber);

        switch (currentToken.type) {
            case IDENFR: {
                // 可能是赋值语句或表达式语句
                int tempIndex = index; // 保存当前 index 和 currentToken，是为了回退到这里
                Token tempToken = currentToken;
                boolean originalOutputEnabled = outputEnabled;
                outputEnabled = false; // outputEnabled 暂时关掉，防止污染调试输出

                // LVal 是左值（Left Value）的意思，比如x = x + 1; x 就是左值（LVal），是被赋值的变量；
                // x + 1 是右值（RVal），是被赋的内容；整个 x = x + 1 是赋值语句 AssignStmt

                ASTNode lvalNode = LVal(); // ✅ 只执行一次 LVal，获取左值（此刻左值被吃掉了）
                System.out.println("[DEBUG][Stmt] 当前 token = " + currentToken);

                if (match(TokenType.ASSIGN)) { // 判断等号，并且吃掉
                    // ✅ 成功匹配到等号，说明这是一个赋值语句

                    // 所以我们回退状态，让变量再重新构建一次 AST 节点👇
                    // 把 index 和 currentToken回到最初的IDENFR位置
                    outputEnabled = originalOutputEnabled;
                    index = tempIndex;
                    currentToken = tempToken;
                    
                    ASTNode assignNode = new ASTNode("AssignStmt"); // 构建assignNode节点
                    System.out.println("[DEBUG][Stmt] 准备匹配赋值语句");
                    System.out.println("[DEBUG][Stmt] 当前 token = " + currentToken);
                    
                    lvalNode = LVal(); // 🔥重新解析LVal！！！此时currentToken会移动
                    assignNode.addChild(lvalNode); // 在assignNode节点挂上之前保存的左值
                    if (!match(TokenType.ASSIGN)) {
                        System.out.println("【DEBUG】【Stmt】匹配=失败，当前Token=" + (currentToken != null ? currentToken.value : "null"));
                        reportError('h'); // 赋值语句缺等号
                    } else {
                        System.out.println("【DEBUG】【Stmt】匹配=成功，currentToken=" + (currentToken != null ? currentToken.value : "null"));
                    }
                    
                    if (currentToken != null && currentToken.type == TokenType.GETINTTK || currentToken.type == TokenType.GETCHARTK) { // 特殊输入赋值
                        // ✅ 是 getint() OR getchar() 输入语句
                        // match(TokenType.GETINTTK); 
                        TokenType inputType = currentToken.type; // 保存一下是哪个
                        match(inputType); // 吃掉！
                        if (!match(TokenType.LPARENT)) {
                            reportError('j');
                        }
                        if (!match(TokenType.RPARENT)) {
                            reportError('j');
                        }
                        // 重新构建AssignStmt节点
                        assignNode = new ASTNode("AssignStmt");
                        assignNode.addChild(lvalNode);
                        // 根据不同输入生成子节点
                        if (inputType == TokenType.GETINTTK) {
                            ASTNode getintNode = new ASTNode("Getint");
                            assignNode.addChild(getintNode);  // ✅ 现在是 Assign 的右边
                        } else {
                            ASTNode getcharNode = new ASTNode("Getchar");
                            assignNode.addChild(getcharNode);
                        }
                        // stmtNode.addChild(assignNode);    // ✅ 把整个赋值语句挂到 stmt 上
                    } else {
                        System.out.println("【DEBUG】【Stmt】准备进入Exp()解析右边表达式，currentToken=" + (currentToken != null ? currentToken.value : "null"));
                        // ✅ 普通赋值语句，如 a = b + 1;
                        ASTNode expNode = Exp(); // 调用 Exp() 解析右边表达式
                        System.out.println("【DEBUG】【Stmt】Exp()解析完成，currentToken=" + (currentToken != null ? currentToken.value : "null"));

                        assignNode.addChild(expNode);
                        // stmtNode.addChild(assignNode);// 整个赋值语句加入语法树
                    }

                    stmtNode.addChild(assignNode);    // 把完整的赋值语句，挂到最外层的 Stmt节点下
                } else {
                    // 表达式语句
                    // ❌ 如果没有匹配到等号，那说明不是赋值语句
                    // 👉 这时候整个语句只能是个表达式语句（如 sum(a, b);） 
                    // 回滚↓，重新当作 "普通表达式语句" 处理
                    outputEnabled = originalOutputEnabled;
                    index = tempIndex;
                    currentToken = tempToken;
                    ASTNode expNode = Exp(); // 整个就是个表达式
                    stmtNode.addChild(expNode); // 表达式语句加进语法树
                    
                }
                // ✅ 最后匹配分号，否则报 i 错（语句未结束）
                // 如果当前的 token 不是分号（;），就说明当前语句没有正确收尾
                System.out.println("exp 后 currentToken = " + (currentToken != null ? currentToken.value : "null"));
                if (!match(TokenType.SEMICN)) { // 吃掉分号！
                    // 拿到当前错误的行号：
                    // 如果 previousToken 有，就用它的行号；
                    // 否则默认行号是 1（防止 null）
                    int errorLineNumber = previousToken != null ? previousToken.lineNumber : 1;
                    // 报告语法错误，类型是 'i'，表示“缺少分号”
                    reportError('i', errorLineNumber);
                }
                break;
            }
            case RETURNTK: {
                match(TokenType.RETURNTK);
                ASTNode returnNode = new ASTNode("Return");
                if (isExpStart()) {
                    ASTNode expNode = Exp();
                    returnNode.addChild(expNode);
                }
                stmtNode.addChild(returnNode);
                if (!match(TokenType.SEMICN)) {
                    int errorLineNumber = previousToken != null ? previousToken.lineNumber : 1;
                    reportError('i', errorLineNumber);
                }
                break;
            }
            
            case PRINTFTK: {
                match(TokenType.PRINTFTK);
                ASTNode printfNode = new ASTNode("Printf");
                if (!match(TokenType.LPARENT)) {
                    reportError('j');
                }
                if (currentToken != null && currentToken.type == TokenType.STRCON) {
                    ASTNode strNode = new ASTNode(currentToken);
                    printfNode.addChild(strNode);
                    match(TokenType.STRCON);
                }
                while (match(TokenType.COMMA)) {
                    ASTNode expNode = Exp();
                    printfNode.addChild(expNode);
                }
                if (!match(TokenType.RPARENT)) {
                    reportError('j');
                }
                if (!match(TokenType.SEMICN)) {
                    reportError('i');
                }
                stmtNode.addChild(printfNode);
                break;
            }
            case SEMICN: {
                // 空语句
                match(TokenType.SEMICN);
                break;
            }

            case BREAKTK: {
                match(TokenType.BREAKTK); // 吃掉break
                ASTNode breakNode = new ASTNode("BreakStmt");
                if (!match(TokenType.SEMICN)) { // break后必须跟分号
                    reportError('i');
                }
                stmtNode.addChild(breakNode); // 把break语句挂到Stmt节点下
                break;
            }
            case CONTINUETK: {
                match(TokenType.CONTINUETK); // 吃掉continue
                ASTNode continueNode = new ASTNode("ContinueStmt");
                if (!match(TokenType.SEMICN)) { // continue后必须跟分号
                    reportError('i');
                }
                stmtNode.addChild(continueNode); // 把continue语句挂到Stmt节点下
                break;
            }

            case LBRACE: {
                // 语句块
                ASTNode blockNode = Block(false);
                stmtNode.addChild(blockNode);
                break;
            }
            case IFTK: {
                System.out.println("【DEBUG】【Parser】开始解析 if 语句");
                match(TokenType.IFTK);
                ASTNode ifNode = new ASTNode("IfStmt");
            
                if (!match(TokenType.LPARENT)) {
                    reportError('j');
                }

                System.out.println("【DEBUG】【Parser】解析 if 条件开始（Cond）");
                ASTNode condNode = Cond();  // ✅ 这里要保存返回的condNode
                ifNode.addChild(condNode);   // ✅ 这里把条件表达式挂上去！
                System.out.println("【DEBUG】【Parser】if 条件Cond节点挂载完成");

                if (!match(TokenType.RPARENT)) {
                    reportError('j');
                }
            
                System.out.println("【DEBUG】【Parser】解析 if 主分支（thenStmt）");
                ASTNode thenStmt = Stmt();  // 处理 if 的主分支
                ifNode.addChild(thenStmt);
                System.out.println("【DEBUG】【Parser】if 主分支挂载完成");
            
                // 处理 else 分支
                if (currentToken != null && currentToken.type == TokenType.ELSETK) {
                    System.out.println("【DEBUG】【Parser】检测到 else 分支，开始解析");
                    match(TokenType.ELSETK);
                    ASTNode elseStmt = Stmt();  // 这里同样支持 { } 或直接语句
                    ifNode.addChild(elseStmt);
                    System.out.println("【DEBUG】【Parser】else 分支挂载完成");
                }else{
                    System.out.println("【DEBUG】【Parser】未检测到 else 分支");
                }
            
                stmtNode.addChild(ifNode);
                System.out.println("【DEBUG】【Parser】if 语句整体挂载到 stmtNode 完成");

                break;
            }
            case FORTK: {
                System.out.println("[DEBUG][Stmt] 匹配到 for 语句");

                match(TokenType.FORTK);
                ASTNode forNode = new ASTNode("ForStmt");
                
                System.out.println("[DEBUG][For] 期待 LPARENT: 当前Token = " + currentToken);
                if (!match(TokenType.LPARENT)) {
                    reportError('j');
                }
                System.out.println("[DEBUG][For] LPARENT 匹配成功");
            
                // ✅ 支持赋值作为 init
                if (!check(TokenType.SEMICN)) {
                    // 如果当前不是 ';' ，说明有 init 表达式
                    System.out.println("[DEBUG][For] 正在解析初始化部分");
                    ASTNode init = parseAssignExpOnly(); //parseAssignExpOnly()负责处理有赋值的exp，返回ASTNode，不处理分号
                    if (init == null || init.getChildren().isEmpty()) {
                        forNode.addChild(new ASTNode("Null")); // 补一个Null节点
                        System.out.println("[DEBUG][For] 初始化为空");
                    } else {
                        forNode.addChild(init);
                        System.out.println("[DEBUG][For] 初始化部分生成成功: " + init.getChildren().get(0).getType());
                    }

                    if (!match(TokenType.SEMICN)) {
                        reportError('j'); // 缺分号
                    }
                } else {
                    forNode.addChild(new ASTNode("Null")); // 补一个Null节点
                    System.out.println("[DEBUG][For] 初始化部分为空，跳过");
                    nextToken(); // 吃掉 ;
                }
            
                // ✅ cond 还是用 Cond()
                if (!check(TokenType.SEMICN)) {
                    System.out.println("[DEBUG][For] 正在解析条件部分");
                    ASTNode condNode = Cond(); // 解析条件并返回 AST
                    forNode.addChild(condNode != null ? condNode : new ASTNode("Null"));
                    System.out.println("[DEBUG][For] 条件部分生成成功");

                    if (!match(TokenType.SEMICN)) {
                        reportError('j'); // 缺分号
                    }
                } else {
                    System.out.println("[DEBUG][For] 条件部分为空，跳过");
                    nextToken(); // 吃掉 ;
                    forNode.addChild(new ASTNode("Null"));
                }
            
                // ✅ 支持赋值作为 step
                System.out.println("[DEBUG][For] 期待 RPARENT: 当前Token = " + currentToken);

                if (check(TokenType.RPARENT)) { // token是右括号
                    nextToken(); // 跳过；表示空步进
                    forNode.addChild(new ASTNode("Null")); // 步进为空也挂一个Null节点
                    System.out.println("[DEBUG][For] 步进为空，直接吃掉右括号");
                } else {
                    // token不是右括号，说明有step内容，继续往下走
                        System.out.println("[DEBUG][For] 正在解析步进部分");
    
                        ASTNode step = parseAssignExpOnly(); // 用 Stmt() 处理 "i=i+1;"
                        forNode.addChild(step);

                        System.out.println("[DEBUG][For] 步进部分生成成功: " + step.getChildren().get(0).getType());

                        System.out.println("[DEBUG][For] 期待 RPARENT: 当前Token = " + currentToken);

                        if (!match(TokenType.RPARENT)) { // 吃掉右括号
                            reportError('j');
                        }
                        System.out.println("[DEBUG][For] RPARENT 匹配成功");
                }
               
                System.out.println("[DEBUG][For] 正在解析 for 循环体语句...");
                ASTNode bodyStmt = Stmt(); // 重新开始新的子语句解析

                if (bodyStmt.getType().equals("Block")) { // Block 是一段 { ... } 花括号包裹的代码块
                    // for 的循环体是 { ... }，我们需要把里面的 BlockItem 提取出来作为循环体
                    // 解开 block，把里面的语句提取出来
                    for (ASTNode child : bodyStmt.getChildren()) {
                        String childInfo = "[DEBUG][For] 子语句类型: " + child.getType();
                        if (!child.getChildren().isEmpty()) {
                            childInfo += " -> 首个子节点类型: " + child.getChildren().get(0).getType();
                        }
                        System.out.println(childInfo); // 打印每个子语句的第一个 token 内容
                        forNode.addChild(child); // 把解析出来的这段循环体代码挂到 forNode 这个 for 循环节点下面
                        System.out.println("[DEBUG][For] 子语句添加成功");
                    }
                } else {
                    // 单条语句直接挂上去
                    forNode.addChild(bodyStmt);
                    System.out.println("[DEBUG][For] 单条语句添加成功");
                }
                System.out.println("[DEBUG][For] 循环体解析完成");

                stmtNode.addChild(forNode);
                System.out.println("[DEBUG][For] ForStmt AST 构建完成，共有 " + forNode.getChildren().size() + " 个子节点");
                break;
            }

            case GETCHARTK: {
                match(TokenType.GETCHARTK);
                match(TokenType.LPARENT);
                match(TokenType.RPARENT);
                match(TokenType.SEMICN);
                ASTNode getcharNode = new ASTNode("GetCharStmt");
                stmtNode.addChild(getcharNode);
                break;
            }            
            
            default: {
                // 表达式语句或其他
                if (isExpStart()) {
                    ASTNode expNode = Exp();
                    stmtNode.addChild(expNode);
                    if (!match(TokenType.SEMICN)) {
                        int errorLineNumber = previousToken != null ? previousToken.lineNumber : 1;
                        reportError('i', errorLineNumber);
                    }
                } else {
                    // 未知的语句类型，跳过当前 token
                    nextToken();
                }
                break;
            }
        }
        
        if (outputEnabled) {
            System.out.println("<Stmt>");
        }
        return stmtNode;
    }

    private boolean check(TokenType type) {
        return currentToken != null && currentToken.type == type;
    }

    private ASTNode LVal() {
        ASTNode lvalNode = new ASTNode("LVal");
        Token identToken = currentToken;
        System.out.println("[DEBUG][LVal] 当前标识符 = " + currentToken.value);

        if (match(TokenType.IDENFR)) {
            lvalNode.addChild(new ASTNode(identToken));
            if (match(TokenType.LBRACK)) {
                ASTNode expNode = Exp();
                lvalNode.addChild(expNode);
                if (!match(TokenType.RBRACK)) {
                    reportError('k');
                }
            }
        }
        return lvalNode;
    }


    // 正确的处理方式：
    // Exp() 就是从AddExp开始解析👇
    // AddExp() 看是不是加号/减号连着的，如果不是就返回MulExp()👇
    // MulExp() 看是不是乘号/除号连着的，如果不是就返回UnaryExp()👇
    // UnaryExp() 看有没有单目运算，如果没有就当PrimaryExp直接读出来！

    private ASTNode Exp() {
        System.out.println("[DEBUG][Exp] 表达式起始 token = " + currentToken.value);
        ASTNode expNode = new ASTNode("Exp"); // 🧱 创建一个 AST 节点，表示 Exp 非终结符（表达式）
        ASTNode addExpNode = AddExp(); // 🌿 调用 AddExp 方法，获取 AddExp 节点（AddExp 是 Exp 的推导式之一）
        expNode.addChild(addExpNode); // 🌳 将 AddExp 节点挂载为 Exp 节点的子节点，建立语法树的父子结构
        return expNode; // 📤 返回构造好的 Exp 语法树节点，供上层语法继续使用
    }

    // 🔥 AddExp → MulExp | AddExp ('+'|'-') MulExp
    // 📖 解析加法减法表达式，处理左右结合性。
    // 🌱 AddExp负责处理比乘除低一层的运算（加减）。
    private ASTNode AddExp() {
        System.out.println("【DEBUG】进入 AddExp - 当前token=" + (currentToken != null ? currentToken.value : "null"));
        ASTNode leftNode = MulExp();
        while (currentToken != null && (currentToken.type == TokenType.PLUS || currentToken.type == TokenType.MINU)) {
            Token opToken = currentToken;
            match(currentToken.type);
            ASTNode rightNode = MulExp();
            
            ASTNode opNode;
            if (opToken.type == TokenType.PLUS) {
                opNode = new ASTNode("AddExpr");
            } else {
                opNode = new ASTNode("SubExpr");
            }
            opNode.addChild(leftNode);
            opNode.addChild(rightNode);
            leftNode = opNode;
        }
        return leftNode;
    }

    private ASTNode MulExp() {
        System.out.println("[DEBUG][MulExp] 开始解析乘除模表达式");

        ASTNode leftNode = UnaryExp();
        System.out.println("[DEBUG][MulExp] 初始左表达式节点类型为: " + leftNode.getType());

        while (currentToken != null && (currentToken.type == TokenType.MULT ||
        currentToken.type == TokenType.DIV ||
        currentToken.type == TokenType.MOD
        )) {

            System.out.println("[DEBUG][MulExp] 当前操作符Token类型: " + currentToken.type);

            Token opToken = currentToken;
            match(currentToken.type);

            ASTNode rightNode = UnaryExp();
            System.out.println("[DEBUG][MulExp] 解析右表达式完成，类型为: " + rightNode.getType());
            
            ASTNode opNode;
            if (opToken.type == TokenType.MULT) {
                System.out.println("[DEBUG][MulExp] 识别为乘法 '*'");
                opNode = new ASTNode("MulExpr");
            } else if(opToken.type == TokenType.DIV){
                System.out.println("[DEBUG][MulExp] 识别为除法 '/'");
                opNode = new ASTNode("DivExpr");
            } else {
                System.out.println("[DEBUG][MulExp] 识别为取模 '%'");
                opNode = new ASTNode("ModExpr");
            }

            opNode.addChild(leftNode);
            opNode.addChild(rightNode);

            System.out.println("[DEBUG][MulExp] 构建AST节点类型为: " + opNode.getType() + 
                           "，并挂载左右子节点: left=" + leftNode.getType() + 
                           ", right=" + rightNode.getType());

            leftNode = opNode;
        }

        System.out.println("[DEBUG][MulExp] 乘除模表达式构造完成，返回AST节点类型: " + leftNode.getType());
        return leftNode;
    }

    private boolean nextIsLPARENT() {
        int temp = index + 1;
        while (temp < tokens.size()) {
            Token token = tokens.get(temp);
            if (token.type == TokenType.ERROR) {
                temp++;
            } else {
                return token.type == TokenType.LPARENT;
            }
        }
        return false;
    }

    // UnaryExp → Ident '(' [Exp { ',' Exp }] ')' | '(' Exp ')' | '+' UnaryExp | '-' UnaryExp | '!' UnaryExp | PrimaryExp
    // UnaryExp就是一元表达式,如：变量名 a,数字 123,函数调用 f(x, y),括号表达式 (x + y),单目操作：+x -x !x
    // 分析并构造一元表达式的 AST 节点
    private ASTNode UnaryExp() { // 函数调用
        ASTNode unaryNode = new ASTNode("UnaryExp");
        System.out.println("【DEBUG】进入 UnaryExp - 当前token=" + currentToken.value);
        System.out.println("【DEBUG】nextIsLPARENT() 返回值: " + nextIsLPARENT());  
    
        // 1. 判断是否为函数调用：Ident '(' ... ')', Ident是函数名字，'('是函数调用开始
        if (currentToken != null && currentToken.type == TokenType.IDENFR && nextIsLPARENT()) {
            // 函数调用 Ident(...)
            Token ident = currentToken; // 保存函数名标识符
            System.out.println("【DEBUG】准备匹配 LPARENT，当前 token = " + (currentToken != null ? currentToken.value : "null"));
            match(TokenType.IDENFR); // 匹配 Ident
            if (match(TokenType.LPARENT)) { // 匹配 '(' 开始参数列表
                ASTNode funcCall = new ASTNode("CallExpr"); // 构建函数调用节点
                funcCall.addChild(new ASTNode(ident));  // 将函数名作为子节点添加
            
                // 2. 判断是否存在实参（支持空参数函数）
                if (isExpStart()) { // 判断是否以表达式开头
                    funcCall.addChild(Exp()); // 解析第一个实参
                    while (match(TokenType.COMMA)) { // 匹配逗号分隔, 处理多个参数
                        funcCall.addChild(Exp()); // 解析下一个实参, 添加后续每个参数
                    }
                }
            
                // 3. 匹配右括号 ')'，若没有则报错
                if (!match(TokenType.RPARENT)) {
                    reportError('j'); // 错误类型 j：缺少右括号 ✅ 第一步：这里会先报缺右括号
                    return null;           // ✅ 第二步：通知“我错了”，然后返回空节点
                }
            
                unaryNode.addChild(funcCall); // 将整个函数调用结构添加为一元表达式子节点
            }
        } else if (currentToken != null && currentToken.type == TokenType.LPARENT) {
            // 括号表达式：(Exp)
            match(TokenType.LPARENT);
            ASTNode expNode = Exp();
            match(TokenType.RPARENT);
            unaryNode.addChild(expNode);
        } else if (currentToken != null &&
                   (currentToken.type == TokenType.PLUS || currentToken.type == TokenType.MINU || currentToken.type == TokenType.NOT)) {
            // 单目运算符
            Token op = currentToken;
            match(op.type);
            ASTNode child = UnaryExp(); // 递归处理子表达式
            ASTNode opNode = new ASTNode(op); // 构建操作符节点
            opNode.addChild(child); // 添加子表达式作为操作符节点的子节点
            unaryNode.addChild(opNode);
        } else if (currentToken != null && currentToken.type == TokenType.IDENFR) {
            // 普通变量名作为表达式
            ASTNode identNode = new ASTNode(currentToken);
            match(TokenType.IDENFR);
            unaryNode.addChild(identNode);  // 将变量视为一个表达式（也可能被处理为函数）
        }else {
            // PrimaryExp → (Exp) | LVal | Number | Char
            unaryNode.addChild(PrimaryExp());
        }
    
        return unaryNode;
    }
    

    private ASTNode PrimaryExp() {
        System.out.println("【DEBUG】进入 PrimaryExp - 当前token=" + currentToken.value);
        ASTNode primaryNode = new ASTNode("PrimaryExp");
        if (match(TokenType.LPARENT)) {
            ASTNode expNode = Exp();
            primaryNode.addChild(expNode);
            if (!match(TokenType.RPARENT)) {
                reportError('j');
            }
        } else if (isLValStart()) {
            ASTNode lval = LVal();
            primaryNode.addChild(lval);
        } else if (currentToken != null && currentToken.type == TokenType.INTCON) {
            ASTNode number = new ASTNode("Number");
            ASTNode literal = new ASTNode(currentToken); // INTCON 本身
            number.addChild(literal);
            primaryNode.addChild(number);
            match(TokenType.INTCON);
        } else if (currentToken != null && currentToken.type == TokenType.CHRCON) {
            ASTNode number = new ASTNode("Number");
            ASTNode literal = new ASTNode(currentToken);
            number.addChild(literal);
            primaryNode.addChild(number);
            match(TokenType.CHRCON); // 吃掉字符常量
        }
        
        return primaryNode;
    }
    

    private boolean isStmt() {
        if (currentToken == null) {
            return false;
        }
        TokenType type = currentToken.type;
        return type == TokenType.IDENFR || type == TokenType.SEMICN || type == TokenType.LBRACE ||
                type == TokenType.IFTK || type == TokenType.FORTK || type == TokenType.BREAKTK ||
                type == TokenType.CONTINUETK || type == TokenType.RETURNTK || type == TokenType.PRINTFTK ||
                type == TokenType.GETINTTK || type == TokenType.GETCHARTK || type == TokenType.PLUS ||
                type == TokenType.MINU || type == TokenType.NOT || type == TokenType.INTCON ||
                type == TokenType.LPARENT || type == TokenType.CHRCON || type == TokenType.STRCON;
    }

    private void ForStmt() {
        Token identToken = currentToken;
        // 解析左值
        ASTNode lvalNode = LVal();  // 修改返回类型

        if (!match(TokenType.ASSIGN)) {
            reportError('k');
        }

        Symbol symbol = currentScope.lookup(identToken.value);
        if (symbol != null && symbol.type.startsWith("Const")) {
            reportError('h', identToken.lineNumber);
        }
        // 解析右侧表达式
        Exp();

        if (outputEnabled) {
            System.out.println("<ForStmt>");
        }
    }
    
    private boolean isForStmtStart() {
        return isLValStart();
    }

    private boolean paramTypesMatch(String formal, String actual) {
        // 如果类型完全相同，则匹配
        if (formal.equals(actual)) {
            return true;
        }
        if ((formal.equals("Int") && actual.equals("Char")) ||
                (formal.equals("Char") && actual.equals("Int"))) {
            return true;
        }
        return false;
    }

    private int countFormatSpecifiers(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '%') {
                // 如果后面还有字符
                if (i + 1 < s.length()) {
                    char next = s.charAt(i + 1);
                    if (next == 'd' || next == 'c') {
                        count++;
                        i++; // 跳过格式字母
                    } else if (next == '%') {
                        i++;
                    }
                }
            }
        }
        return count;
    }

    // 判断是否是表达式的开始符号
    private boolean isExpStart() {
        if (currentToken == null) {
            return false;
        }
        TokenType type = currentToken.type;
        return type == TokenType.IDENFR || type == TokenType.INTCON || type == TokenType.LPARENT ||
                type == TokenType.PLUS || type == TokenType.MINU || type == TokenType.NOT ||
                type == TokenType.CHRCON;
    }

    // Cond → LOrExp
    private ASTNode Cond() {
        System.out.println("=== Cond() 进入 === 当前Token是：" + currentToken);

        ASTNode node = LOrExp();  // ✅ 解析条件表达式
        if (outputEnabled) {
            System.out.println("x:<Cond>");
        }
        return node;  // ✅ 别忘了返回！
    }

    // LOrExp → LAndExp | LOrExp '||' LAndExp
    private ASTNode LOrExp() {
        System.out.println("=== LOrExp() 进入 === 当前Token是：" + currentToken);

        ASTNode left = LAndExp();  // 第一个子表达式
        if (outputEnabled) {
            System.out.println("x:<LOrExp>");
        }
    
        while (currentToken != null && currentToken.getType() == TokenType.OR) {
            match(TokenType.OR);
            ASTNode right = LAndExp();  // 后面的表达式
            ASTNode newNode = new ASTNode("LOrExp");
            newNode.addChild(left);    // 左边
            newNode.addChild(right);   // 右边
            left = newNode;            // 继续向上构造
            if (outputEnabled) {
                System.out.println("x:<LOrExp>");
            }
        }
    
        return left;  // 最终构建的 OR 表达式树
    }
    

    // LAndExp → EqExp | LAndExp '&&' EqExp
    private ASTNode LAndExp() {
        System.out.println("=== LAndExp() 进入 === 当前Token是：" + currentToken);

        ASTNode left = EqExp();  // 第一个
    
        while (currentToken != null && currentToken.getType() == TokenType.AND) {
            match(TokenType.AND);
            ASTNode right = EqExp();  // 右边一个
            ASTNode newNode = new ASTNode("LAndExp");
            newNode.addChild(left);
            newNode.addChild(right);
            left = newNode;  // 上升为下一层
        }
    
        if (outputEnabled) {
            System.out.println("x:<LAndExp>");
        }
    
        return left;
    }

    // EqExp → RelExp | EqExp ('==' | '!=') RelExp
    private ASTNode EqExp() {
        System.out.println("=== EqExp() 进入 === 当前Token是：" + currentToken);

        ASTNode left = RelExp();
    
        while (currentToken != null && 
               (currentToken.getType() == TokenType.EQL || currentToken.getType() == TokenType.NEQ)) {
            TokenType op = currentToken.getType();
            match(op);
            ASTNode right = RelExp();
            ASTNode newNode = new ASTNode("EqExp_" + op);  // 可命名为 EqExp_EQL
            newNode.addChild(left);
            newNode.addChild(right);
            left = newNode;
        }
    
        if (outputEnabled) {
            System.out.println("x:<EqExp>");
        }
    
        return left;
    }

    // RelExp → AddExp | RelExp ('<' | '>' | '<=' | '>=' | '==' | '!=') AddExp
    private ASTNode RelExp() {
        System.out.println("=== RelExp() 进入 === 当前Token是：" + currentToken);

        ASTNode left = AddExp();
        System.out.println("[DEBUG][Parser] RelExp 左边AddExp解析完成，当前Token是：" + currentToken);
    
        while (currentToken != null &&
               (currentToken.getType() == TokenType.LSS ||  // <
                currentToken.getType() == TokenType.GRE ||  // >
                currentToken.getType() == TokenType.LEQ ||  // <=
                currentToken.getType() == TokenType.GEQ ||  // >=
                currentToken.getType() == TokenType.EQL ||  // ==
                currentToken.getType() == TokenType.NEQ     // !=
            )) {
            System.out.println("[DEBUG][Parser] RelExp 检测到比较运算符，当前Token是：" + currentToken);
            TokenType op = currentToken.getType();
            match(op); // 吃掉比较符号
            System.out.println("[DEBUG][Parser] RelExp 匹配并吃掉比较符号 " + op + " 后，当前Token是：" + currentToken);

            ASTNode right = AddExp(); // 右边也要解析AddExp
            System.out.println("[DEBUG][Parser] RelExp 右边AddExp解析完成，当前Token是：" + currentToken);

            ASTNode newNode = new ASTNode("RelExp_" + op);
            newNode.addChild(left);
            newNode.addChild(right);
            left = newNode; // 更新left指针指向新的RelExp节点
            System.out.println("[DEBUG][Parser] RelExp 创建新节点 RelExp_" + op + " 完成");
        }
    
        if (outputEnabled) {
            System.out.println("x:<RelExp>");
        }
    
        return left; // 返回最终解析出来的RelExp
    }

    // 只解析赋值，不吃分号
    private ASTNode parseAssignExpOnly() {
        ASTNode assignNode = new ASTNode("AssignExp"); // 自定义的专用于for的可赋值表达式节点

        // 解析 LVal
        ASTNode lvalNode = LVal();

        // 匹配等号
        if (!match(TokenType.ASSIGN)) {
            // 报错，比如"for的init或step期望出现=号"
            int errorLineNumber = previousToken != null ? previousToken.lineNumber : 1;
            reportError('h', errorLineNumber); // h是赋值错误常用error code
        }

        // 解析 Exp
        ASTNode expNode = Exp();

        // 组装成赋值节点
        assignNode.addChild(lvalNode);
        assignNode.addChild(expNode);

        return assignNode;
    }


    private boolean isLValStart() {
        return currentToken != null && currentToken.type == TokenType.IDENFR;
    }

    private boolean isBlockItem() {
        return isDecl() || isStmt();
    }
}
