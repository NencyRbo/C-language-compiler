package frontend;

import java.io.*;
import java.util.*;

public class Lexer {
    public List<Error> errors = new ArrayList<>();
    public Set<Integer> errorLines = new HashSet<>();
    private Map<String, TokenType> RESERVED_WORDS;

    public Lexer() {
        RESERVED_WORDS = new HashMap<>();
        RESERVED_WORDS.put("const", TokenType.CONSTTK);
        RESERVED_WORDS.put("int", TokenType.INTTK);
        RESERVED_WORDS.put("break", TokenType.BREAKTK);
        RESERVED_WORDS.put("continue", TokenType.CONTINUETK);
        RESERVED_WORDS.put("if", TokenType.IFTK);
        RESERVED_WORDS.put("else", TokenType.ELSETK);
        RESERVED_WORDS.put("for", TokenType.FORTK);
        RESERVED_WORDS.put("main", TokenType.MAINTK);
        RESERVED_WORDS.put("void", TokenType.VOIDTK);
        RESERVED_WORDS.put("return", TokenType.RETURNTK);
        RESERVED_WORDS.put("char", TokenType.CHARTK);
        RESERVED_WORDS.put("getchar", TokenType.GETCHARTK);
        RESERVED_WORDS.put("printf", TokenType.PRINTFTK);
        RESERVED_WORDS.put("getint", TokenType.GETINTTK);
        RESERVED_WORDS.put("!", TokenType.NOT);
        RESERVED_WORDS.put("&&", TokenType.AND);
        RESERVED_WORDS.put("||", TokenType.OR);
        RESERVED_WORDS.put("*", TokenType.MULT);
        RESERVED_WORDS.put("%", TokenType.MOD);
        RESERVED_WORDS.put("+", TokenType.PLUS);
        RESERVED_WORDS.put("-", TokenType.MINU);
        RESERVED_WORDS.put("=", TokenType.ASSIGN);
        RESERVED_WORDS.put(";", TokenType.SEMICN);
        RESERVED_WORDS.put(",", TokenType.COMMA);
        RESERVED_WORDS.put("<", TokenType.LSS);
        RESERVED_WORDS.put("<=", TokenType.LEQ);
        RESERVED_WORDS.put(">", TokenType.GRE);
        RESERVED_WORDS.put(">=", TokenType.GEQ);
        RESERVED_WORDS.put("==", TokenType.EQL);
        RESERVED_WORDS.put("!=", TokenType.NEQ);
        RESERVED_WORDS.put("(", TokenType.LPARENT);
        RESERVED_WORDS.put(")", TokenType.RPARENT);
        RESERVED_WORDS.put("[", TokenType.LBRACK);
        RESERVED_WORDS.put("]", TokenType.RBRACK);
        RESERVED_WORDS.put("{", TokenType.LBRACE);
        RESERVED_WORDS.put("}", TokenType.RBRACE);
    }

    public List<Token> tokenize(String fileName) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder contentBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                contentBuilder.append(line);
                contentBuilder.append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        String input = contentBuilder.toString();
        int length = input.length();
        int pos = 0;
        int lineNumber = 1;

        while (pos < length) {
            char current = input.charAt(pos);
            if (isWhitespace(current)) {
                if (current == '\n') {
                    lineNumber++;
                }
                pos++;
                continue;
            }

            // 处理 '/',注释或除法运算符
            if (current == '/') {
                if (pos + 1 < length) {
                    char next = input.charAt(pos + 1);
                    // 单行注释
                    if (next == '/') {
                        pos += 2;
                        while (pos < length && input.charAt(pos) != '\n') {
                            pos++;
                        }
                        continue;
                    }
                    // 多行注释
                    else if (next == '*') {
                        pos += 2;
                        boolean foundEnd = false;
                        while (pos < length) {
                            if (input.charAt(pos) == '*' && pos + 1 < length && input.charAt(pos + 1) == '/') {
                                pos += 2;
                                foundEnd = true;
                                break;
                            } else {
                                if (input.charAt(pos) == '\n') {
                                    lineNumber++;
                                }
                                pos++;
                            }
                        }
                        if (!foundEnd) {
                            if (!errorLines.contains(lineNumber)) {
                                errors.add(new Error(lineNumber, 'a'));
                                errorLines.add(lineNumber);
                            }
                        }
                        continue;
                    } else {
                        // 既不是注释，处理为 DIV 运算符
                        int tokenLine = lineNumber;
                        tokens.add(new Token(TokenType.DIV, "/", tokenLine));
                        pos++;
                        continue;
                    }
                } else {
                    int tokenLine = lineNumber;
                    tokens.add(new Token(TokenType.DIV, "/", tokenLine));
                    pos++;
                    continue;
                }
            }

            int tokenLine = lineNumber;
            // 处理标识符或保留字
            if (isLetter(current) || current == '_') {
                int start = pos;
                while (pos < length && (isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                    pos++;
                }
                // ✔️ 标识符或关键字
                String lexeme = input.substring(start, pos);
                TokenType type = RESERVED_WORDS.getOrDefault(lexeme, TokenType.IDENFR);
                tokens.add(new Token(type, lexeme, tokenLine));

                // ✅ 添加调试输出：
                System.out.println("[DEBUG]" + lexeme + " 是合法的 " + type.name());
                continue;
            }
            // 处理数字常量
            else if (isDigit(current)) {
                int start = pos;
                while (pos < length && isDigit(input.charAt(pos))) {
                    pos++;
                }
                String lexeme = input.substring(start, pos);
                tokens.add(new Token(TokenType.INTCON, lexeme, tokenLine));

                // ✅ 添加调试输出：
                System.out.println("[DEBUG] 整数常量 " + lexeme + " at line " + tokenLine);
                continue;
            }
            // 处理字符串常量
            else if (current == '"') {
                int start = pos;
                pos++; // 跳过起始双引号
                StringBuilder strBuilder = new StringBuilder();
                strBuilder.append('"');
                boolean closed = false;
                while (pos < length) {
                    char ch = input.charAt(pos);
                    if (ch == '"') {
                        // 检查前面的反斜杠数量判断是否被转义
                        int backslashCount = 0;
                        int temp = pos - 1;
                        while (temp >= start + 1 && input.charAt(temp) == '\\') {
                            backslashCount++;
                            temp--;
                        }
                        if (backslashCount % 2 == 0) { // 未被转义
                            strBuilder.append('"');
                            pos++;
                            closed = true;
                            break;
                        } else {
                            strBuilder.append(ch);
                            pos++;
                        }
                    } else {
                        if (ch == '\n') {
                            lineNumber++;
                        }
                        strBuilder.append(ch);
                        pos++;
                    }
                }
                if (!closed) {
                    if (!errorLines.contains(tokenLine)) {
                        errors.add(new Error(tokenLine, 'a'));
                        errorLines.add(tokenLine);
                    }
                }
                String lexeme = strBuilder.toString();

                // 💥 去掉两边的引号（前提是你strBuilder是从 " 开始 append 的）
                if (lexeme.length() >= 2 && lexeme.startsWith("\"") && lexeme.endsWith("\"")) {
                    lexeme = lexeme.substring(1, lexeme.length() - 1);
                }
                
                tokens.add(new Token(TokenType.STRCON, lexeme, tokenLine));

                // ✅ 添加调试输出：
                System.out.println("[DEBUG] 字符串常量 " + lexeme + " at line " + tokenLine);
                continue;
            }
            // 处理字符常量
            else if (current == '\'') {
                int start = pos;
                pos++; // 跳过起始单引号
                StringBuilder charBuilder = new StringBuilder();
                charBuilder.append('\'');
                boolean closed = false;
                if (pos < length) {
                    char ch = input.charAt(pos);
                    // 如果为转义字符
                    if (ch == '\\') {
                        charBuilder.append(ch);
                        pos++;
                        if (pos < length) {
                            char escapeChar = input.charAt(pos);
                            charBuilder.append(escapeChar);
                            pos++;
                        } else {
                            if (!errorLines.contains(tokenLine)) {
                                errors.add(new Error(tokenLine, 'a'));
                                errorLines.add(tokenLine);
                            }
                        }
                    } else {
                        charBuilder.append(ch);
                        pos++;
                    }
                    // 期待结束的单引号
                    if (pos < length && input.charAt(pos) == '\'') {
                        charBuilder.append('\'');
                        pos++;
                        closed = true;
                    }
                }
                if (!closed) {
                    if (!errorLines.contains(tokenLine)) {
                        errors.add(new Error(tokenLine, 'a'));
                        errorLines.add(tokenLine);
                    }
                }
                String lexeme = charBuilder.toString();
                tokens.add(new Token(TokenType.CHRCON, lexeme, tokenLine));
                continue;
            }
            // 处理其他运算符和符号
            else {
                if (current == '<') {
                    if (pos + 1 < length && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.LEQ, "<=", tokenLine));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.LSS, "<", tokenLine));
                        pos++;
                    }
                    continue;
                } else if (current == '>') {
                    if (pos + 1 < length && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.GEQ, ">=", tokenLine));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.GRE, ">", tokenLine));
                        pos++;
                    }
                    continue;
                } else if (current == '=') {
                    if (pos + 1 < length && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.EQL, "==", tokenLine));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.ASSIGN, "=", tokenLine));
                        pos++;
                    }
                    continue;
                } else if (current == '!') {
                    if (pos + 1 < length && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.NEQ, "!=", tokenLine));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.NOT, "!", tokenLine));
                        pos++;
                    }
                    continue;
                } else if (current == '&') {
                    if (pos + 1 < length && input.charAt(pos + 1) == '&') {
                        tokens.add(new Token(TokenType.AND, "&&", tokenLine));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.AND, "&", tokenLine));
                        if (!errorLines.contains(tokenLine)) {
                            errors.add(new Error(tokenLine, 'a'));
                            errorLines.add(tokenLine);
                        }
                        pos++;
                    }
                    continue;
                } else if (current == '|') {
                    if (pos + 1 < length && input.charAt(pos + 1) == '|') {
                        tokens.add(new Token(TokenType.OR, "||", tokenLine));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.OR, "|", tokenLine));
                        if (!errorLines.contains(tokenLine)) {
                            errors.add(new Error(tokenLine, 'a'));
                            errorLines.add(tokenLine);
                        }
                        pos++;
                    }
                    continue;
                } else if (current == '+') {
                    tokens.add(new Token(TokenType.PLUS, "+", tokenLine));
                    pos++;
                    continue;
                } else if (current == '-') {
                    tokens.add(new Token(TokenType.MINU, "-", tokenLine));
                    pos++;
                    continue;
                } else if (current == '*') {
                    tokens.add(new Token(TokenType.MULT, "*", tokenLine));
                    pos++;
                    continue;
                } else if (current == '%') {
                    tokens.add(new Token(TokenType.MOD, "%", tokenLine));
                    pos++;
                    continue;
                } else if (current == ';') {
                    tokens.add(new Token(TokenType.SEMICN, ";", tokenLine));
                    pos++;
                    continue;
                } else if (current == ',') {
                    tokens.add(new Token(TokenType.COMMA, ",", tokenLine));
                    pos++;
                    continue;
                } else if (current == '(') {
                    tokens.add(new Token(TokenType.LPARENT, "(", tokenLine));
                    pos++;
                    continue;
                } else if (current == ')') {
                    tokens.add(new Token(TokenType.RPARENT, ")", tokenLine));
                    pos++;
                    continue;
                } else if (current == '[') {
                    tokens.add(new Token(TokenType.LBRACK, "[", tokenLine));
                    pos++;
                    continue;
                } else if (current == ']') {
                    tokens.add(new Token(TokenType.RBRACK, "]", tokenLine));
                    pos++;
                    continue;
                } else if (current == '{') {
                    tokens.add(new Token(TokenType.LBRACE, "{", tokenLine));
                    pos++;
                    continue;
                } else if (current == '}') {
                    tokens.add(new Token(TokenType.RBRACE, "}", tokenLine));
                    pos++;
                    continue;
                } else {
                    // 无法识别的字符，记录错误
                    tokens.add(new Token(TokenType.ERROR, String.valueOf(current), tokenLine));
                    if (!errorLines.contains(tokenLine)) {
                        errors.add(new Error(tokenLine, 'a'));
                        errorLines.add(tokenLine);
                    }
                    pos++;
                    continue;
                }
            }
        }
        return tokens;
    }

    // 判断空白字符
    private boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    // 判断字母
    private boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // 判断数字
    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // 判断字母或数字
    private boolean isLetterOrDigit(char c) {
        return isLetter(c) || isDigit(c);
    }
}
