package frontend;

import java.util.ArrayList;
import java.util.List;

public class Symbol {
    public String name; // 标识符名称
    public String type; // 类型名称，如 ConstInt、Int、IntFunc 等
    public int scopeLevel; // 作用域序号
    public boolean isParam = false; // 默认为局部变量，用于区分参数和局部变量

    public int level = 0; // 用于记录变量的层级，用于生成中间代码时的地址计算
    public int offset = -1; // ✅ 用于记录变量地址（代码生成中用于 LOD/STO 的 offset）

    public List<String> paramTypes = new ArrayList<>(); // 形参类型列表，仅对函数有效
    public boolean isConst;
    public Symbol(String name, String type, int scopeLevel) {
        this.name = name;
        this.type = type;
        this.scopeLevel = scopeLevel;
        this.level = scopeLevel; // <-- 修正：将传入的 scopeLevel 赋值给 level 字段
    }
}
