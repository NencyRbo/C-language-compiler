package frontend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PCode {
    public enum OpCode {
        LIT,    // 取常数
        LOD,    // 读变量
        STO,    // 写变量
        ADD, SUB, MUL, DIV, MOD, // 算术运算
        EQL, NEQ, LSS, LEQ, GTR, GEQ, // 比较运算 (新增 EQL, NEQ, GTR, GEQ)
        JMP, JPC,                // 跳转、条件跳转
        CALL, RET,               // 函数调用返回
        SWAP,                    // 交换栈顶两个元素 (新增)
        PRINT,                   // 输出
        PRINTSTR,                // 输出字符串常量 ✅新加
        READ,                    // 输入
        POP,                     // 弹出栈顶元素 (新增)
        OR,AND,NOT,              // 逻辑运算 (新增 OR, AND, NOT)
        INT,                     // 栈帧分配 (新增)
    }
    private OpCode op;
    private int level;
    private int address;
    private int paramCount = -1; // 新增：用于 CALL 指令，记录参数个数, -1 for others

    public PCode(OpCode op, int level, int address) {
        this(op, level, address, -1); // 调用新的构造函数，paramCount 默认为 -1
    }

    // 新增构造函数，用于 CALL 指令
    public PCode(OpCode op, int level, int address, int paramCount) {
        this.op = op;
        this.level = level;
        this.address = address;
        this.paramCount = paramCount;
    }

    public void setAddress(int address) {
        this.address = address;
    }

    public OpCode getOp() {
        return op;
    }

    public int getLevel() {
        return level;
    }

    public int getAddress() { // 新增 getter
        return address;
    }

    public int getParamCount() { // 新增 getter
        return paramCount;
    }

    @Override
    public String toString() {
        if (op == OpCode.CALL) {
            return op + " " + level + " " + address + " (" + paramCount + " params)"; // CALL 特殊处理
        }
        return op + " " + level + " " + address;
    }
}
