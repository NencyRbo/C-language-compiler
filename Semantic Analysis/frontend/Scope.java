package frontend;

import java.util.*;

public class Scope {
    private Map<String, Symbol> symbols = new LinkedHashMap<>();
    Scope parentScope;
    private int scopeLevel;

    public Scope(Scope parentScope, int scopeLevel) {
        this.parentScope = parentScope;
        this.scopeLevel = scopeLevel;
    }

    public Symbol lookup(String name) {
        Symbol symbol = symbols.get(name);
        if (symbol != null) {
            return symbol;
        } else if (parentScope != null) {
            return parentScope.lookup(name);
        } else {
            return null;
        }
    }

    public boolean declare(Symbol symbol) {
        if (symbols.containsKey(symbol.name)) {
            return false; // 重定义
        } else {
            symbols.put(symbol.name, symbol);
            return true;
        }
    }

    public Collection<Symbol> getSymbols() {
        return symbols.values();
    }

    public int getScopeLevel() {
        return scopeLevel;
    }
}
