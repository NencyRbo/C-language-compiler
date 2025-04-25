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
        READ                     // 输入
    }

    private OpCode op;
    private int level;
    private int address;

    public PCode(OpCode op, int level, int address) {
        this.op = op;
        this.level = level;
        this.address = address;
    }

    public OpCode getOp() {
        return op;
    }

    public int getLevel() {
        return level;
    }

    public int getAddress() {
        return address;
    }

    @Override
    public String toString() {
        return op + " " + level + " " + address;
    }
}
