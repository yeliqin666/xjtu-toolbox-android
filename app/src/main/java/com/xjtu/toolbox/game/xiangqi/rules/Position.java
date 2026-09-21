package com.xjtu.toolbox.game.xiangqi.rules;

import java.io.Serializable;

public class Position implements Serializable {
    private static final long serialVersionUID = 1L;

    public int x;
    public int y;

    public Position(int x, int y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Position)) {
            return false;
        }
        Position position = (Position) obj;
        return this.x == position.x && this.y == position.y;
    }

    // write toString method
    @Override
    public String toString() {
        return String.format("(%d, %d)", x, y);
    }
}
