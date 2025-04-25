package frontend;

import java.util.ArrayList;
import java.util.List;

public class Symbol {
    public String name; // 标识符名称
    public String type; // 类型名称，如 ConstInt、Int、IntFunc 等
    public int scopeLevel; // 作用域序号
    public List<String> paramTypes = new ArrayList<>(); // 形参类型列表，仅对函数有效

    public Symbol(String name, String type, int scopeLevel) {
        this.name = name;
        this.type = type;
        this.scopeLevel = scopeLevel;
    }
}
