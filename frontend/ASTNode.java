package frontend;

import java.util.ArrayList;
import java.util.List;

public class ASTNode {
    private String name;
    private Token token;
    private List<ASTNode> children;
    private String source;

    public ASTNode(String name) {
        this.name = name;
        this.children = new ArrayList<>();
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSource() {
        return this.source;
    }

    public ASTNode(Token token) {
        this.token = token;
        this.name = token.type.name();
        this.children = new ArrayList<>();
    }

    public void addChild(ASTNode child) {
        this.children.add(child);
    }

    public String getName() {
        return name;
    }

    public Token getToken() {
        return token;
    }

    public List<ASTNode> getChildren() {
        return children;
    }

    // ✅ CodeGenerator 用的三大接口：

    public String getType() {
        return name;  // 把 name 作为类型名返回
    }

    public String getValue() {
        if (token != null) {
            return token.value;
        } else {
            return name;  // 或者返回名字作为备选
        }
    }
}
