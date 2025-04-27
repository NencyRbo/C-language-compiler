package frontend;

import java.io.*;
import java.util.*;

public class PCodeExecutor {
    private List<PCode> instructions;
    private int[] memory = new int[100]; // 简易内存模拟变量区
    // private Stack<Integer> stack = new Stack<>();
    // 控制栈（专用于 CALL/RET 保存返回地址）
    private Stack<StackFrame> callStack = new Stack<>();
    // 数据栈（用于 LOD, ADD 等运算）
    private Stack<Integer> dataStack = new Stack<>();
    private BufferedWriter writer;
    private int pc = 0; // 程序计数器
    private Scanner scanner = new Scanner(System.in);
    private List<String> stringPool = CodeGenerator.stringPool;
    boolean stepByStep = false; // 默认开启单步调试

    public void setStringPool(List<String> pool) {
        this.stringPool = pool;
    }

    // 映射函数入口地址 → 变量数
    Map<Integer, Integer> funcVarCountByEntryPC = new HashMap<>();

    boolean hasLoggedCall = false;
    boolean hasReturnedOnce = false;

    int bp = 0; // base pointer，当前帧的变量起始地址
    // stack pointer，指向当前 memory 使用到哪
    int sp = 0; // 当前 memory 的使用上限（每次函数调用 + 局部变量大小）

    // 添加一个常量表示栈底标记或者初始调用者地址，避免返回到随机地址或 0
    private static final int END_OF_EXECUTION_MARKER = -1; 

    public PCodeExecutor(List<PCode> instructions) {
        this.instructions = instructions;
    }

    public void setPC(int pc) {
        this.pc = pc;
    }

    private static class StackFrame {
        int returnAddr;
        int base;
    
        public StackFrame(int returnAddr, int base) {
            this.returnAddr = returnAddr;
            this.base = base;
        }
        @Override
        public String toString() {
            return "[ret=" + returnAddr + ", base=" + base + "]";
        }
    }

    private static String parseEscapes(String s) {
        return s
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    public void execute() {
        System.out.println("[DEBUG] PCodeExecutor: 开始执行，共 " + instructions.size() + " 条指令");
        // 初始时在栈底放入结束标记，用于识别主程序返回
        // callStack.push(END_OF_EXECUTION_MARKER);
        callStack.push(new StackFrame(-1, -1)); // 表示主函数结束点
        System.out.println("[DEBUG] PCodeExecutor: 主函数结束点已入栈");
        // System.out.println("[DEBUG] PCodeExecutor: 初始化，栈底标记: " + END_OF_EXECUTION_MARKER);

        try {
            writer = new BufferedWriter(new FileWriter("data/pcoderesult.txt"));
            System.out.println("[DEBUG] PCodeExecutor: 开始执行，指令总数: " + instructions.size());

            while (pc >= 0 && pc < instructions.size()) { // 确保 pc 在有效范围内
                PCode inst = instructions.get(pc);

                // 插入单步调试提示 👇
                if (stepByStep) {
                    System.out.println("[DEBUG] 当前PC=" + pc + ", 准备执行指令=" + inst);
                    System.out.print("按回车继续下一步，输入q后回车退出... ");
                    String input = scanner.nextLine();
                    if ("q".equalsIgnoreCase(input.trim())) {
                        System.out.println("[DEBUG] 用户请求终止执行，退出PCode执行器！");
                        break;
                    }
                }
                // 👆单步调试完毕！

                PCode.OpCode op = inst.getOp();
                System.out.println("[DEBUG] === PC: " + pc + ", 指令: " + inst + ", 栈顶: " + (dataStack.isEmpty() ? "空" : dataStack.peek()) + " ===");

                int currentPC = pc; // 保存当前 PC，用于日志和 CALL
                pc++; // 默认情况下，PC 指向下一条指令

                switch (op) {
                    case LIT:
                        int literal = inst.getAddress();
                        dataStack.push(literal);
                        System.out.println("[DEBUG] LIT: 将常量 " + literal + " 压栈. 栈: " + dataStack);
                        break;

                    case LOD:
                        int loadAddr = bp + inst.getAddress();
                        if (loadAddr < 0 || loadAddr >= memory.length) {
                            System.err.println("[ERROR] LOD: 无效内存地址 " + loadAddr);
                            throw new RuntimeException("Invalid memory address for LOD: " + loadAddr);
                        }
                        int loadedValue = memory[loadAddr];
                        dataStack.push(loadedValue);
                        System.out.println("[DEBUG] LOD: 从地址 " + loadAddr + " 加载值 " + loadedValue + " 压栈. 栈: " + dataStack);
                        break;

                    case STO:
                        if (dataStack.isEmpty()) {
                            System.err.println("[ERROR] STO: 栈为空，无法存储!");
                            throw new RuntimeException("Stack underflow on STO");
                        }
                        int valueToStore = dataStack.pop();
                        int storeAddr = bp + inst.getAddress();
                         if (storeAddr < 0 || storeAddr >= memory.length) {
                            System.err.println("[ERROR] STO: 无效内存地址 " + storeAddr);
                            dataStack.push(valueToStore); // 恢复栈状态
                             throw new RuntimeException("Invalid memory address for STO: " + storeAddr);
                        }
                        memory[storeAddr] = valueToStore;
                        System.out.println("[DEBUG] STO: 将值 " + valueToStore + " 存储到地址 " + storeAddr + ". 栈: " + dataStack + ", 内存["+storeAddr+"]=" + memory[storeAddr]);
                        break;

                    case ADD:
                        if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on ADD");
                        int addB = dataStack.pop(); int addA = dataStack.pop(); int addRes = addA + addB;
                        dataStack.push(addRes);
                        System.out.println("[DEBUG] ADD: " + addA + " + " + addB + " = " + addRes + ". 栈: " + dataStack);
                        break;

                    case SUB:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on SUB");
                         int subB = dataStack.pop(); int subA = dataStack.pop(); int subRes = subA - subB;
                         dataStack.push(subRes);
                         System.out.println("[DEBUG] SUB: " + subA + " - " + subB + " = " + subRes + ". 栈: " + dataStack);
                         break;

                    case MUL:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on MUL");
                         int mulB = dataStack.pop(); int mulA = dataStack.pop(); int mulRes = mulA * mulB;
                         dataStack.push(mulRes);
                         System.out.println("[DEBUG] MUL: " + mulA + " * " + mulB + " = " + mulRes + ". 栈: " + dataStack);
                         break;

                     case DIV:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on DIV");
                         int divB = dataStack.pop(); int divA = dataStack.pop();
                         if (divB == 0) throw new RuntimeException("Division by zero");
                         int divRes = divA / divB;
                         dataStack.push(divRes);
                         System.out.println("[DEBUG] DIV: " + divA + " / " + divB + " = " + divRes + ". 栈: " + dataStack);
                         break;

                    case MOD:
                        if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on MOD");
                        int modB = dataStack.pop(); int modA = dataStack.pop();
                        if (modB == 0) throw new RuntimeException("Modulo by zero");
                        int modRes = modA % modB;
                        dataStack.push(modRes);
                        System.out.println("[DEBUG] MOD: " + modA + " % " + modB + " = " + modRes + ". 栈: " + dataStack);
                        break;

                    case SWAP:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on SWAP");
                         int swapB = dataStack.pop(); int swapA = dataStack.pop();
                         dataStack.push(swapB); dataStack.push(swapA);
                         System.out.println("[DEBUG] SWAP: 交换栈顶两元素. 栈: " + dataStack);
                         break;

                    case EQL:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on EQL");
                         int eqlB = dataStack.pop(); int eqlA = dataStack.pop();
                         dataStack.push(eqlA == eqlB ? 1 : 0);
                         System.out.println("[DEBUG] EQL: " + eqlA + " == " + eqlB + " -> " + dataStack.peek() + ". 栈: " + dataStack);
                         break;

                    // 无条件跳转（jump）
                    // 直接跳！不管栈顶的值！
                    // 	for循环跳回判断，if-then后跳到if结束
                    case JMP:
                        int jmpAddr = inst.getAddress();
                        System.out.println("[DEBUG] JMP: 无条件跳转到地址 " + jmpAddr);
                        pc = jmpAddr;
                        break;

                    // 条件跳转（Jump if Condition）
                    // 弹出栈顶元素，如果是0就跳，否则继续。
                    // if条件判断失败跳到else或者出口，for判断失败跳出循环
                    case JPC:
                         if (dataStack.isEmpty()) throw new RuntimeException("Stack underflow on JPC");
                         int condition = dataStack.pop();
                         int jpcAddr = inst.getAddress();
                         System.out.println("[DEBUG] JPC: 条件值为 " + condition + ". 跳转地址 " + jpcAddr);
                         if (condition == 0) {
                             System.out.println("[DEBUG] JPC: 条件为 0, 跳转");
                             pc = jpcAddr;
                         } else {
                              System.out.println("[DEBUG] JPC: 条件非 0, 不跳转");
                         }
                         break;

                    case GTR:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on GTR");
                         int gtrB = dataStack.pop(); 
                         int gtrA = dataStack.pop();
                         dataStack.push(gtrA > gtrB ? 1 : 0);
                         System.out.println("[DEBUG] GTR: " + gtrA + " > " + gtrB + " -> " + dataStack.peek() + ". 栈: " + dataStack);
                         break;
                    
                    case LSS:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on LSS");
                         int lssB = dataStack.pop(); 
                         int lssA = dataStack.pop();
                         dataStack.push(lssA < lssB ? 1 : 0);
                         System.out.println("[DEBUG] LSS: " + lssA + " < " + lssB + " -> " + dataStack.peek() + ". 栈: " + dataStack);
                         break;
                     
                    case LEQ:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on LEQ");
                         int leqB = dataStack.pop(); 
                         int leqA = dataStack.pop();
                         dataStack.push(leqA <= leqB ? 1 : 0);
                         System.out.println("[DEBUG] LEQ: " + leqA + " <= " + leqB + " -> " + dataStack.peek() + ". 栈: " + dataStack);
                         break;
                     
                    case GEQ:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on GEQ");
                         int geqB = dataStack.pop(); 
                         int geqA = dataStack.pop();
                         dataStack.push(geqA >= geqB ? 1 : 0);
                         System.out.println("[DEBUG] GEQ: " + geqA + " >= " + geqB + " -> " + dataStack.peek() + ". 栈: " + dataStack);
                         break;
                     
                    case NEQ:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on NEQ");
                         int neqB = dataStack.pop(); 
                         int neqA = dataStack.pop();
                         dataStack.push(neqA != neqB ? 1 : 0);
                         System.out.println("[DEBUG] NEQ: " + neqA + " != " + neqB + " -> " + dataStack.peek() + ". 栈: " + dataStack);
                         break;

                    case OR:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on OR");
                         int orB = dataStack.pop(); 
                         int orA = dataStack.pop();
                         dataStack.push((orA != 0 || orB != 0) ? 1 : 0);
                         System.out.println("[DEBUG] OR: " + orA + " || " + orB + " -> " + dataStack.peek() + ". 栈: " + dataStack);
                         break;
                     
                    case AND:
                         if (dataStack.size() < 2) throw new RuntimeException("Stack underflow on AND");
                         int andB = dataStack.pop(); 
                         int andA = dataStack.pop();
                         dataStack.push((andA != 0 && andB != 0) ? 1 : 0);
                         System.out.println("[DEBUG] AND: " + andA + " && " + andB + " -> " + dataStack.peek() + ". 栈: " + dataStack);
                         break;

                    case PRINT:
                        if (dataStack.isEmpty()) throw new RuntimeException("Stack underflow on PRINT");
                        int valueToPrint = dataStack.pop();
                        System.out.println("[OUTPUT] " + valueToPrint); // ✅ 打印到控制台
                        writer.write(valueToPrint + "\n"); // ✅ 写入 pcoderesult.txt
                        System.out.println("[DEBUG] PRINT: 输出值 " + valueToPrint + ". 栈: " + dataStack);
                        break;
                    
                    case PRINTSTR:
                        String raw = stringPool.get(inst.getAddress());
                        String parsed = parseEscapes(raw);  // 原始字符串 + 转义处理
                    
                        // 构建格式化后的最终字符串
                        StringBuilder sb = new StringBuilder();
                        int i = 0;
                    
                        while (i < parsed.length()) {
                            char ch = parsed.charAt(i);
                            if (ch == '%' && i + 1 < parsed.length()) {
                                char next = parsed.charAt(i + 1);
                                switch (next) {
                                    case 'd':
                                        if (dataStack.isEmpty()) throw new RuntimeException("printf: 缺少 %d 参数");
                                        sb.append(dataStack.pop());
                                        break;
                                    case 'c':
                                        if (dataStack.isEmpty()) throw new RuntimeException("printf: 缺少 %c 参数");
                                        sb.append((char) (int) dataStack.pop());  // 注意类型转换
                                        break;
                                    case 's':
                                        if (dataStack.isEmpty()) throw new RuntimeException("printf: 缺少 %s 参数");
                                        int strIndex = dataStack.pop();
                                        if (strIndex < 0 || strIndex >= stringPool.size())
                                            throw new RuntimeException("printf: %s 字符串索引非法");
                                        sb.append(parseEscapes(stringPool.get(strIndex)));
                                        break;
                                    default:
                                        sb.append('%').append(next); // 非格式化指令，原样输出
                                }
                                i += 2;
                            } else {
                                sb.append(ch);
                                i++;
                            }
                        }
                    
                        String finalOutput = sb.toString();
                        System.out.println("[OUTPUT] " + finalOutput);
                        writer.write(finalOutput);
                        break;                    

                    case READ:
                        try {
                            // 简化处理：直接从控制台读取，或确保 input.txt 存在且有效
                            System.out.print("[INPUT] 请输入一个整数: ");
                            Scanner consoleScanner = new Scanner(System.in);
                            int inputValue = consoleScanner.nextInt();
                            dataStack.push(inputValue);
                            System.out.println("[DEBUG] READ: 读取输入 " + inputValue + " 压栈. 栈: " + dataStack);
                        } catch (Exception e) {
                             System.err.println("[ERROR] READ: 读取输入失败! " + e.getMessage());
                             throw new RuntimeException("Failed to read input", e);
                        }
                        break;

                    case RET:
                        System.out.println("[DEBUG] 执行 RET 指令");

                        // 如果栈为空，说明是主函数返回，直接终止程序
                        if (callStack.isEmpty()) {
                            System.out.println("[DEBUG] 栈为空，主函数返回，程序终止");
                            // return;
                            break;  // ✅ 完全跳出解释器执行循环！！
                        }

                        System.out.println("[DEBUG] RET 执行前栈内容：" + callStack);
                        System.out.println("[DEBUG] 数据栈内容: " + dataStack); // 这是执行 LOD、ADD 的主栈
                        // int returnAddr = callStack.pop();
                        StackFrame frame = callStack.pop();
                        bp = frame.base;
                        sp = frame.base; // 回收空间
                        pc = frame.returnAddr;
                        System.out.println("[DEBUG] RET 弹出函数返回地址: " + pc);
                        printStackStatus();

                        // if (!hasReturnedOnce) {
                        //     hasReturnedOnce = true;
                        //     System.out.println("💥 [DEBUG] 第一次 RET 执行完毕，强制终止程序用于调试！");
                        //     return; // ✅ 立刻退出主循环，防止死循环
                        // }

                        // 如果栈不为空，弹出返回地址并继续执行
                        // pc = returnAddr;
                        continue;

                    case CALL:
                        System.out.println("[DEBUG] 当前执行 CALL 的 PC 是: " + currentPC);    
                        // 保存返回地址（下一条指令的地址）
                        int returnAddr = currentPC + 1; // ✅ 当前指令的下一条才是要返回的地方
                        // callStack.push(returnAddr);
                        // 保存当前帧的 base
                        callStack.push(new StackFrame(returnAddr, bp));  // ✅ 保存返回地址和当前bp
                        bp = sp;                // ✅ 切换 base 为当前可用区域
                        sp += 10;               // ✅ 假设每个函数最多需要10个变量空间（你可换成动态计算）

                        // if (!hasLoggedCall) {
                        //     System.out.println("======== 💥 DEBUG 断点触发 💥 ========");
                        //     System.out.println("[DEBUG] 当前 PC = " + pc);
                        //     System.out.println("[DEBUG] CALL 指令地址 = " + inst.getAddress());
                        //     System.out.println("[DEBUG] CALL 实际压入的返回地址: " + (pc + 1));
                        //     System.out.println("[DEBUG] 栈当前状态: " + stack);
                        //     hasLoggedCall = true; // ✅ 保证只打印一次
                        
                        //     // 💣 关键一招：终止程序
                        //     System.out.println("💥 程序在第一次 CALL 时终止，退出分析！");
                        //     return; // ✅ 直接终止解释器执行！
                        // }

                        // stack.push(currentPC + 1); // ✅ 先自增，再压栈
                        // 跳转到函数入口
                        // ✅ 跳转到函数入口地址（inst.getAddress() 是 CALL 指令携带的跳转目标）
                        pc = inst.getAddress(); // 函数入口
                        System.out.println("[DEBUG] CALL: 跳转到函数入口地址 " + pc);
                        printStackStatus();
                        continue; // 跳过 pc++

                    default:
                        System.err.println("[ERROR] 未知 OpCode: " + op + " at PC=" + currentPC);
                        throw new RuntimeException("Unknown OpCode: " + op);
                }
            }

            if (pc >= instructions.size()) {
                 System.out.println("[DEBUG] PCodeExecutor: 执行超出指令列表末尾.");
            } else if (pc < 0 && !callStack.isEmpty()) { // 检查当前帧的 returnAddr 是否为 -1
                 StackFrame top = callStack.peek();
                 if (top.returnAddr == END_OF_EXECUTION_MARKER) {
                    System.out.println("[DEBUG] PCodeExecutor: 主程序正常返回.");
                 }
            } else {
                 System.out.println("[DEBUG] PCodeExecutor: 执行意外终止，PC = " + pc + ", 栈顶: " + (callStack.isEmpty()?"空":callStack.peek()));
            }
            writer.close();
        } catch (Exception e) {
            System.err.println("\n[FATAL ERROR] PCodeExecutor 执行出错: " + e.getMessage());
            e.printStackTrace();
            // 尝试关闭 writer
            if (writer != null) {
                try { writer.close(); } catch (IOException ioex) { /* ignore */ }
            }
        }
    }

    // ✅ 统一调试输出函数
    private void printStackStatus() {
        System.out.println("📦 [STACK INFO]");
        System.out.println(" - callStack: " + callStack);     // 显示函数调用栈帧
        System.out.println(" - dataStack: " + dataStack);     // 显示表达式栈
        System.out.println(" - bp = " + bp + ", sp = " + sp); // 当前函数帧边界
        System.out.print(" - memory: [");
        for (int i = 0; i < memory.length; i++) {
            if (memory[i] != 0) {
                System.out.print(i + "=" + memory[i] + ", ");
            }
        }
        System.out.println("]");
    }
}
