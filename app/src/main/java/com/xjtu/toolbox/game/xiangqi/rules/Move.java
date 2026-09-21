package com.xjtu.toolbox.game.xiangqi.rules;


import static java.lang.Math.abs;

import java.io.Serializable;
import java.util.HashMap;

public class Move implements Serializable {
    private static final long serialVersionUID = 1L;

    public Board board = null;

    public Position fromPosition;
    public Position toPosition;
    public int piece;

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        if(comment != null) {
            comment = comment.strip();
            comment = comment.replace("&nbsp;", " ");
        }
        this.comment = comment;
    }

    public String comment;

    // create hashmap to convert Arabic numerals to Chinese numerals
    public static final HashMap<Integer, String> arabicToChineseMap = new HashMap<>();
    static {
        arabicToChineseMap.put(1, "一");
        arabicToChineseMap.put(2, "二");
        arabicToChineseMap.put(3, "三");
        arabicToChineseMap.put(4, "四");
        arabicToChineseMap.put(5, "五");
        arabicToChineseMap.put(6, "六");
        arabicToChineseMap.put(7, "七");
        arabicToChineseMap.put(8, "八");
        arabicToChineseMap.put(9, "九");
    }

    public Move(Position fromPosition, Position toPosition, Board board) {
        this.fromPosition = fromPosition;
        this.toPosition = toPosition;
        this.board = board;
        updatePieceInfo();
    }

    public Move(Position fromPosition, Position toPosition) {
        this.fromPosition = fromPosition;
        this.toPosition = toPosition;
    }

    public Move(Board board) {
        this.board = new Board(board);
    }

    public void setBoard(Board board) {
        this.board = new Board(board);
        updatePieceInfo();
    }

    public void setPositions(Position fromPosition, Position toPosition){
        this.fromPosition = fromPosition;
        this.toPosition = toPosition;
        updatePieceInfo();
    }

    private void updatePieceInfo(){
        if(board == null || fromPosition == null || toPosition == null){
            return;
        }
        piece = board.getPieceByPosition(fromPosition);
    }


    public boolean fromUCCIString(String ucciString){
        if(ucciString == null || ucciString.length() != 4){
            return false;
        }

        char s, e;
        s = ucciString.charAt(0);
        e = ucciString.charAt(2);
        Position pos = new Position(s - 'a', 9 - (ucciString.charAt(1) - '0'));
        if(!Board.isValidPosition(pos)){
            return false;
        } else {
            fromPosition = pos;
        }
        pos = new Position(e - 'a', 9 - (ucciString.charAt(3) - '0'));
        if(!Board.isValidPosition(pos)){
            return false;
        } else {
            toPosition = pos;
        }

        updatePieceInfo();

        return true;
    }

    // h2e2
    public String getUCCIString(){
        char s, e;
        s = (char)('a' + fromPosition.x);
        e = (char)('a' + toPosition.x);
        String result = String.format("%c%d%c%d", s, 9 - fromPosition.y, e, 9 - toPosition.y);
        return result;
    }

    /*
    * 从一个位置移动到另一个位置的中文描述
    * 例如：车一进六, 炮七退七, 相七进九, 帅四平五, 帅五进一, 将五退一, 未知动作
    * 这个函数的设计比较复杂，需要考虑的因素很多：
    * 1. 对于横轴来讲，红方是从右到左数的，黑方是从左到右数的
    * 2. 对于纵轴来讲，红方是从下到上是进，黑方是从下到上是退
    * 3. 对于走直线的棋子，需要考虑是进退（最后一个数字是纵坐标的差值），还是平移（最后一个数字是目标位置的横坐标）
    * 4. 对于走斜线的棋子（马、相、士），需要考虑是进退，最后一个数字是目标位置的横坐标
    * 5. 对于红字移动，使用中文的数字；对于黑子移动，使用阿拉伯数字
    * 6. 还需要处理同一列有相同的棋子，使用前后来区分，比如前炮退二，后炮进二？？
    * https://www.xqbase.com/protocol/cchess_move.htm
     */
    public String getChsString(){
        if(board == null){
            // depends on board status
            return "未知动作";
        }
        int piece = board.getPieceByPosition(fromPosition);
        if(Piece.isValid(piece)) {
            char name = Piece.pieceNameMap.get(piece);
            String num1, action, num2;

            if(Piece.isRed(piece)) {
                num1 = arabicToChineseMap.get(9 - fromPosition.x);

                action = "平";
                // 3.对于走直线的棋子，需要考虑是进退（最后一个数字是纵坐标的差值），还是平移（最后一个数字是目标位置的横坐标）
                num2 = arabicToChineseMap.get(abs(toPosition.y - fromPosition.y));
                if(toPosition.y > fromPosition.y){
                    action = "退";
                } else if(toPosition.y < fromPosition.y){
                    action = "进";
                } else {
                    // 3.对于走直线的棋子，需要考虑是进退（最后一个数字是纵坐标的差值），还是平移（最后一个数字是目标位置的横坐标）
                    num2 = arabicToChineseMap.get(9 - toPosition.x);
                }

                if(Piece.isDiagonalPiece(piece)) {
                    // 4. 对于走斜线的棋子（马、相、士），需要考虑是进退，最后一个数字是目标位置的横坐标
                    num2 = arabicToChineseMap.get(9 - toPosition.x);
                }

                // 6.还需要处理同一列有相同的棋子，使用前后来区分，比如前炮退二，后炮进二.
                String multiple = findPiecesOnSameVLine(piece, fromPosition);
                if(multiple == null){
                    return name + num1 + action + num2;
                } else {
                    return multiple + name + action + num2;
                }
            } else if(Piece.isBlack(piece)) {
                num1 = String.format("%d", fromPosition.x + 1);

                action = "平";
                // 3.对于走直线的棋子，需要考虑是进退（最后一个数字是纵坐标的差值），还是平移（最后一个数字是目标位置的横坐标）
                num2 = String.format("%d", abs(toPosition.y - fromPosition.y));
                if(toPosition.y > fromPosition.y){
                    action = "进";
                } else if(toPosition.y < fromPosition.y){
                    action = "退";
                } else {
                    // 3.对于走直线的棋子，需要考虑是进退（最后一个数字是纵坐标的差值），还是平移（最后一个数字是目标位置的横坐标）
                    num2 = String.format("%d", toPosition.x + 1);
                }

                if(Piece.isDiagonalPiece(piece)) {
                    // 4. 对于走斜线的棋子（马、相、士），需要考虑是进退，最后一个数字是目标位置的横坐标
                    num2 = String.format("%d", toPosition.x + 1);
                }

                // 6.还需要处理同一列有相同的棋子，使用前后来区分，比如前炮退二，后炮进二.
                String multiple = findPiecesOnSameVLine(piece, fromPosition);
                if(multiple == null){
                    return name + num1 + action + num2;
                } else {
                    return multiple + name + action + num2;
                }
            }
        }
        return "未知动作";
    }

    /*
    find same piece on the same vertical line
    https://www.xqbase.com/protocol/cchess_move.htm
     */
    private String findPiecesOnSameVLine(int piece, Position pos) {
        if(piece == Piece.BJIANG || piece == Piece.BXIANG || piece == Piece.BSHI
                || piece == Piece.WSHUAI || piece == Piece.WXIANG || piece == Piece.WSHI)
        {  // 仕(士)和相(象)如果在同一纵线上，不用“前”和“后”区别，因为能退的一定在前，能进的一定在后
            return null;
        }

        // 找到这一列里有多少相同的子
        int start, end, step;
        if(Piece.isRed(piece)) {
            start = 0;
            end = 10;
            step = 1;
        } else {
            start = 9;
            end = -1;
            step = -1;
        }
        int count = 0;
        int index = 0;
        for (int i = start; i != end; i += step) {
            int p = board.getPieceByPosition(pos.x, i);
            if(p == piece){
                count++;
                if(pos.y == i){
                    index = count;
                }
            }
        }

        if(count == 1){
            // 没有重复的子
            return null;
        }
        if(piece != Piece.WBING && piece != Piece.BZU) {
            // 非兵卒，这时count只能为2
            if(index == 1) {
                return "前";
            } else {
                return "后";
            }
        }

        // 开始处理兵卒的情况，特别复杂，需要看其他纵线上有没有一个以上的兵，如果有，还要看这些兵的位置，再决定如何标记
        // 在有两条纵线，每条纵线上都有一个以上的兵：按照“先从右到左，再从前到后”(即先看最左边一列，
        // 从前到后依次标记为“一”和“二”，可能还有“三”，再看右边一列)的顺序，把这些兵的位置标依次
        // 标记为“一”、“二”、“三”、“四”和“五”，不在这两条纵线上的兵不参与标记
        // 具体可以看 https://www.xqbase.com/protocol/cchess_move.htm
        // ***这里我们假设其他纵线没有多于一个以上的兵的情况 ^0^，等你遇到了来报bug吧***

        // 三个以下兵在一条纵线上：用“前”、“中”和“后”来区别；
        if(index == 1 && count <=3) {
            return "前";
        }
        if(index == count && count <=3) {
            return "后";
        }
        if(index == 2 && count ==3) {
            return "中";
        }
        // 三个以上兵在一条纵线上：最前面的兵用“一”代替“前”，以后依次是“二”、“三”、“四”和“五”；
        if(Piece.isRed(piece)){
            return String.format("%s",arabicToChineseMap.get(index));
        } else {
            return String.format("%d",index);
        }
    }

    // create toString
    @Override
    public String toString() {
        return "Move{" +
                 fromPosition +
                " => " + toPosition +
                ", piece=" + piece +
                ", comment='" + comment + '\'' +
                '}';
    }
}
