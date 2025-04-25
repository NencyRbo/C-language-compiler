package frontend;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

public class Parser {
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

    public Parser(List<Token> tokens, List<Error> errors, Set<Integer> errorLines) {
        this.tokens = tokens;
        this.errors = errors;
        this.errorLines = errorLines; // 使用共享的 errorLines 集合
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
            Decl();
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
    private void Decl() {
        if (isConstDecl()) {
            ConstDecl();
        } else if (isVarDecl()) {
            VarDecl();
        } else {
            // 不可能到这里，报告错误
            reportError('k');
        }
    }

    // 判断是否是常量声明
    private boolean isConstDecl() {
        return currentToken != null && currentToken.type == TokenType.CONSTTK;
    }

    // ConstDecl → 'const' BType ConstDef { ',' ConstDef } ';' // i
    private void ConstDecl() {
        if (!match(TokenType.CONSTTK)) {
            reportError('k');
            return;
        }
        BType();
        ConstDef();
        while (match(TokenType.COMMA)) {
            ConstDef();
        }
        if (!match(TokenType.SEMICN)) {
            int errorLineNumber = previousToken != null ? previousToken.lineNumber : 1;
            reportError('i', errorLineNumber);
        }
        // 输出 <ConstDecl>
        if (outputEnabled) {
            System.out.println("<ConstDecl>");
        }
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
    private void VarDecl() {
        BType();
        VarDef();
        while (match(TokenType.COMMA)) {
            VarDef();
        }
        if (!match(TokenType.SEMICN)) {
            int errorLineNumber = previousToken != null ? previousToken.lineNumber : 1;
            reportError('i', errorLineNumber);
        }
        // 输出 <VarDecl>
        if (outputEnabled) {
            System.out.println("<VarDecl>");
        }
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
    private void ConstExp() {
        AddExp();
        // 输出 <ConstExp>
        if (outputEnabled) {
            System.out.println("<ConstExp>");
        }
    }

    // VarDef → Ident [ '[' ConstExp ']' ] | Ident [ '[' ConstExp ']' ] '=' InitVal // k
    private void VarDef() {
        Token identToken = currentToken;
        if (!match(TokenType.IDENFR)) {
            reportError('k');
            return;
        }
        String typeName = ""; // 类型名称
        if (match(TokenType.LBRACK)) {
            ConstExp();
            if (!match(TokenType.RBRACK)) {
                reportError('k');
            }
            typeName = currentBType.equals("int") ? "IntArray" : "CharArray";
        } else {
            typeName = currentBType.equals("int") ? "Int" : "Char";
        }
        if (match(TokenType.ASSIGN)) {
            InitVal();
        }

        // 检查符号重定义
        if (!currentScope.declare(new Symbol(identToken.value, typeName, currentScope.getScopeLevel()))) {
            reportError('b', identToken.lineNumber);
        }
        if (outputEnabled) {
            System.out.println("<VarDef>");
        }
    }


    // ConstDef → Ident [ '[' ConstExp ']' ] '=' ConstInitVal // k
    private void ConstDef() {
        Token identToken = currentToken;
        if (!match(TokenType.IDENFR)) {
            reportError('k');
            return;
        }
        String typeName = ""; // 类型名称
        if (match(TokenType.LBRACK)) {
            ConstExp();
            if (!match(TokenType.RBRACK)) {
                reportError('k');
            }
            typeName = currentBType.equals("int") ? "ConstIntArray" : "ConstCharArray";
        } else {
            typeName = currentBType.equals("int") ? "ConstInt" : "ConstChar";
        }
        if (!match(TokenType.ASSIGN)) {
            reportError('k');
            return;
        }
        ConstInitVal();

        // 检查符号重定义
        if (!currentScope.declare(new Symbol(identToken.value, typeName, currentScope.getScopeLevel()))) {
            reportError('b', identToken.lineNumber);
        }
        if (outputEnabled) {
            System.out.println("<ConstDef>");
        }
    }


    // ConstInitVal → ConstExp | '{' [ ConstExp { ',' ConstExp } ] '}' | StringConst
    private void ConstInitVal() {
        if (match(TokenType.LBRACE)) {
            if (!match(TokenType.RBRACE)) {
                ConstExp();
                while (match(TokenType.COMMA)) {
                    ConstExp();
                }
                if (!match(TokenType.RBRACE)) {
                    reportError('k');
                }
            }
        } else if (currentToken != null && currentToken.type == TokenType.STRCON) {
            match(TokenType.STRCON);
        } else {
            ConstExp();
        }
        // 输出 <ConstInitVal>
        if (outputEnabled) {
            System.out.println("<ConstInitVal>");
        }
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
        // 🔄 重置当前函数是否有语法错误的标志位，每次进入新的函数定义都得初始化
    
        TokenType funcType = currentToken.type;
        String funcTypeName = getFuncTypeName(funcType);
        currentFuncType = funcTypeName;
        // 🔍 获取函数返回类型（int/void/char），并存储为当前函数的返回类型（供 return 语句检查使用）
    
        FuncType(); // 吃掉 int/void/char
        // 🧹 吃掉函数类型的 Token，移到下一个 Token
    
        if (!match(TokenType.IDENFR)) return null;
        // ❌ 如果没有函数名，直接返回。虽然这里没报错，但返回后语义分析必然报错
        Token funcNameToken = previousToken;
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
        }
        // 🚨 如果该作用域内已经定义了同名函数，报告重定义错误 'b'
    
        if (!match(TokenType.LPARENT)) {
            reportError('j', funcNameToken.lineNumber); // 🧩 函数名后面必须跟左括号 ( 否则就是语法错误 'j'
            return funcNode; // 即使出错也返回节点，保持 AST 完整性 
        }
    
        ASTNode paramListNode = new ASTNode("FuncFParams"); // 即使空也加进去
        // 🧶 不管有没有参数，都先建一个参数列表节点，方便统一结构处理
        enterScope(); // 🚪 进入函数体作用域，参数变量应该注册在函数内部作用域中
    
        if (match(TokenType.RPARENT)) {
            // 空参数
            // ✅ 空参数函数，直接吃掉右括号，啥都不做
        } else if (isFuncFParamsStart()) {
            FuncFParams(funcSymbol); // 你原本是处理符号，不构造AST
            // ⚠️ 这里也可以再构造 paramListNode 并填参数节点
            // 🧠 解析参数列表，并添加到符号表 funcSymbol.paramTypes 中
            if (!match(TokenType.RPARENT)) {
                reportError('j', funcNameToken.lineNumber);
            } // 🚨 参数列表后缺少右括号，报错类型 'j'
        } else {
            reportError('j', funcNameToken.lineNumber); // ❌ 函数名后既不是 ) 也不是参数开头，那说明是错的
        }
    
        funcNode.addChild(paramListNode); // 把参数节点挂上 // ✅ 即使参数为空，也加入 AST，保持结构一致性
    
        ASTNode blockNode = Block(true); // 🧱 解析函数体（Block），true 表示这是函数块，用于 return 检查等
        funcNode.addChild(blockNode); // ✅ 将整个函数体加入 AST
    

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
    
        if (outputEnabled) {
            System.out.println("<FuncDef>"); // 📤 输出语法成分标签，适用于调试和输出语法分析过程
        }

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
    private void FuncFParams(Symbol funcSymbol) {
        FuncFParam(funcSymbol);
        while (match(TokenType.COMMA)) {
            FuncFParam(funcSymbol);
        }
        // 输出 <FuncFParams>
        if (outputEnabled) {
            System.out.println("<FuncFParams>");
        }
    }

    // FuncFParam → BType Ident ['[' ']'] // k
    private void FuncFParam(Symbol funcSymbol) {
        if (currentToken != null && (currentToken.type == TokenType.INTTK || currentToken.type == TokenType.CHARTK)) {
            String bType = currentToken.value;
            BType();
            Token identToken = currentToken;
            if (!match(TokenType.IDENFR)) {
                // 错误处理
                return;
            }
            String typeName = "";
            if (match(TokenType.LBRACK)) {
                if (!match(TokenType.RBRACK)) {
                    reportError('k');
                }
                typeName = bType.equals("int") ? "IntArray" : "CharArray";
            } else {
                typeName = bType.equals("int") ? "Int" : "Char";
            }

            // 将参数类型添加到函数符号的 paramTypes 中
            funcSymbol.paramTypes.add(typeName);

            // 检查符号重定义
            if (!currentScope.declare(new Symbol(identToken.value, typeName, currentScope.getScopeLevel()))) {
                reportError('b', identToken.lineNumber);
            }
            if (outputEnabled) {
                System.out.println("<FuncFParam>");
            }
        } else {
            // 错误处理
        }
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
            while (isBlockItem()) {
                ASTNode itemNode = BlockItem();
                if (itemNode != null) {
                    blockNode.addChild(itemNode);
                }
            }
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
            Decl();
            return null;  // 声明不需要生成中间代码
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

        switch (currentToken.type) {
            case IDENFR: {
                // 可能是赋值语句或表达式语句
                int tempIndex = index;
                Token tempToken = currentToken;
                boolean originalOutputEnabled = outputEnabled;
                outputEnabled = false;

                LVal(); // 先尝试匹配一个变量/数组左值
                if (match(TokenType.ASSIGN)) {
                    // 赋值语句
                    // ✅ 成功匹配到等号，说明这是一个赋值语句
                    // 所以我们回退状态，让变量再重新构建一次 AST 节点
                    outputEnabled = originalOutputEnabled;
                    index = tempIndex;
                    currentToken = tempToken;
                    
                    ASTNode assignNode = new ASTNode("AssignStmt");
                    ASTNode lvalNode = LVal(); // 再次获取左值
                    assignNode.addChild(lvalNode);
                    
                    match(TokenType.ASSIGN); // 吃掉 '='
                    
                    if (currentToken != null && currentToken.type == TokenType.GETINTTK) {
                        // ✅ 是 getint() 输入语句
                        match(TokenType.GETINTTK);
                        if (!match(TokenType.LPARENT)) {
                            reportError('j');
                        }
                        if (!match(TokenType.RPARENT)) {
                            reportError('j');
                        }
                        assignNode = new ASTNode("AssignStmt");
                        assignNode.addChild(lvalNode);
                        ASTNode getintNode = new ASTNode("Getint");
                        assignNode.addChild(getintNode);  // ✅ 现在是 Assign 的右边
                        stmtNode.addChild(assignNode);    // ✅ 把整个赋值语句挂到 stmt 上
                    } else {
                        // ✅ 普通赋值语句，如 a = b + 1;
                        ASTNode expNode = Exp(); // 赋值右边是普通表达式
                        assignNode.addChild(expNode);
                        stmtNode.addChild(assignNode);// 整个赋值语句加入语法树
                    }
                } else {
                    // 表达式语句
                    // ❌ 如果没有匹配到等号，那说明不是赋值语句
                    // 👉 这时候整个语句只能是个表达式语句（如 sum(a, b);） 
                    outputEnabled = originalOutputEnabled;
                    index = tempIndex;
                    currentToken = tempToken;
                    ASTNode expNode = Exp(); // 整个就是个表达式
                    stmtNode.addChild(expNode); // 表达式语句加进语法树
                    
                }
                // ✅ 最后匹配分号，否则报 i 错（语句未结束）
                // 如果当前的 token 不是分号（;），就说明当前语句没有正确收尾
                System.out.println("exp 后 currentToken = " + (currentToken != null ? currentToken.value : "null"));
                if (!match(TokenType.SEMICN)) {
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
            case LBRACE: {
                // 语句块
                ASTNode blockNode = Block(false);
                stmtNode.addChild(blockNode);
                break;
            }
            case IFTK: {
                match(TokenType.IFTK);
                ASTNode ifNode = new ASTNode("IfStmt");
            
                if (!match(TokenType.LPARENT)) {
                    reportError('j');
                }
                Cond();  // 条件表达式
                if (!match(TokenType.RPARENT)) {
                    reportError('j');
                }
            
                ASTNode thenStmt = Stmt();  // 处理 if 的主分支
                ifNode.addChild(thenStmt);
            
                // 处理 else 分支
                if (currentToken != null && currentToken.type == TokenType.ELSETK) {
                    match(TokenType.ELSETK);
                    ASTNode elseStmt = Stmt();  // 这里同样支持 { } 或直接语句
                    ifNode.addChild(elseStmt);
                }
            
                stmtNode.addChild(ifNode);
                break;
            }
            case WHILETK: {
                match(TokenType.WHILETK);
                ASTNode whileNode = new ASTNode("WhileStmt");
            
                if (!match(TokenType.LPARENT)) {
                    reportError('j');
                }
                Cond();
                if (!match(TokenType.RPARENT)) {
                    reportError('j');
                }
            
                ASTNode bodyStmt = Stmt();
                whileNode.addChild(bodyStmt);
                stmtNode.addChild(whileNode);
                break;
            }
            case FORTK: {
                match(TokenType.FORTK);
                ASTNode forNode = new ASTNode("ForStmt");
            
                if (!match(TokenType.LPARENT)) {
                    reportError('j');
                }
            
                // 初始表达式（可选）
                if (!check(TokenType.SEMICN)) {
                    ASTNode init = Exp();
                    forNode.addChild(init);
                }
                match(TokenType.SEMICN);
            
                // 条件表达式（可选）
                if (!check(TokenType.SEMICN)) {
                    Cond();
                }
                match(TokenType.SEMICN);
            
                // 更新表达式（可选）
                if (!check(TokenType.RPARENT)) {
                    ASTNode step = Exp();
                    forNode.addChild(step);
                }
                if (!match(TokenType.RPARENT)) {
                    reportError('j');
                }
            
                ASTNode bodyStmt = Stmt();
                forNode.addChild(bodyStmt);
                stmtNode.addChild(forNode);
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

    private ASTNode Exp() {
        ASTNode expNode = new ASTNode("Exp"); // 🧱 创建一个 AST 节点，表示 Exp 非终结符（表达式）
        ASTNode addExpNode = AddExp(); // 🌿 调用 AddExp 方法，获取 AddExp 节点（AddExp 是 Exp 的推导式之一）
        expNode.addChild(addExpNode); // 🌳 将 AddExp 节点挂载为 Exp 节点的子节点，建立语法树的父子结构
        return expNode; // 📤 返回构造好的 Exp 语法树节点，供上层语法继续使用
    }

    private ASTNode AddExp() {
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
        ASTNode leftNode = UnaryExp();
        while (currentToken != null && (currentToken.type == TokenType.MULT ||
        currentToken.type == TokenType.DIV ||
        currentToken.type == TokenType.MOD)) {
            Token opToken = currentToken;
            match(currentToken.type);
            ASTNode rightNode = UnaryExp();
            
            ASTNode opNode;
            if (opToken.type == TokenType.MULT) {
                opNode = new ASTNode("MulExpr");
            } else if(opToken.type == TokenType.DIV){
                opNode = new ASTNode("DivExpr");
            } else {
                opNode = new ASTNode("ModExpr");
            }
            opNode.addChild(leftNode);
            opNode.addChild(rightNode);
            leftNode = opNode;
        }
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
    private void Cond() {
        LOrExp();
        // 输出 <Cond>
        if (outputEnabled) {
            System.out.println("<Cond>");
        }
    }

    // LOrExp → LAndExp | LOrExp '||' LAndExp
    private void LOrExp() {
        LAndExp();
        if (outputEnabled) {
            System.out.println("<LOrExp>");
        }
        while (currentToken != null) {
            if (match(TokenType.OR)) {
                LAndExp();
                if (outputEnabled) {
                    System.out.println("<LOrExp>");
                }
            } else if (currentToken.type == TokenType.ERROR) {
                reportError('a', currentToken.lineNumber); // 或者适当的错误类型
                hasSyntaxErrorInCurrentFunc = true;
                nextToken(); // 跳过错误的符号
            } else {
                break;
            }
        }
    }

    // LAndExp → EqExp | LAndExp '&&' EqExp
    private void LAndExp() {
        EqExp();
        if (outputEnabled) {
            System.out.println("<LAndExp>");
        }
        while (match(TokenType.AND)) {
            EqExp();
            if (outputEnabled) {
                System.out.println("<LAndExp>");
            }
        }
    }

    // EqExp → RelExp | EqExp ('==' | '!=') RelExp
    private void EqExp() {
        RelExp();
        if (outputEnabled) {
            System.out.println("<EqExp>");
        }
        while (currentToken != null && (currentToken.type == TokenType.EQL || currentToken.type == TokenType.NEQ)) {
            match(currentToken.type);
            RelExp();
            if (outputEnabled) {
                System.out.println("<EqExp>");
            }
        }
    }

    // RelExp → AddExp | RelExp ('<' | '>' | '<=' | '>=') AddExp
    private void RelExp() {
        AddExp();
        if (outputEnabled) {
            System.out.println("<RelExp>");
        }
        while (currentToken != null && (currentToken.type == TokenType.LSS || currentToken.type == TokenType.GRE ||
                currentToken.type == TokenType.LEQ || currentToken.type == TokenType.GEQ)) {
            match(currentToken.type);
            AddExp();
            if (outputEnabled) {
                System.out.println("<RelExp>");
            }
        }
    }

    private boolean isLValStart() {
        return currentToken != null && currentToken.type == TokenType.IDENFR;
    }

    private boolean isBlockItem() {
        return isDecl() || isStmt();
    }
}
