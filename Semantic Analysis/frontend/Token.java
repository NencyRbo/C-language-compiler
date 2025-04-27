package frontend;

// Token 是词法分析生成的词（关键字、标识符、字符串、数字等）
public class Token {
    public TokenType type;
    public String value;
    public int lineNumber;
     
    public Token(TokenType type, String value, int lineNumber) {
        this.type = type;
        this.value = value;
        this.lineNumber = lineNumber;
    }
     public String toString() {
        return type.name() + " " + value;
    }
     public TokenType getType() {
        return type;
     }
}