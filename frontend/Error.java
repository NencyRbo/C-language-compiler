package frontend;

public class Error {
    public int lineNumber;
    public char errorType;

    public Error(int lineNumber, char errorType) {
        this.lineNumber = lineNumber;
        this.errorType = errorType;
    }
}