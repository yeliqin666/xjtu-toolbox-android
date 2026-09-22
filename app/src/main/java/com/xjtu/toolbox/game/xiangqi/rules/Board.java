package com.xjtu.toolbox.game.xiangqi.rules;


import java.io.Serializable;
import java.util.ArrayList;

/**
 * Created by zfdang on 2016-4-17.
 * This class represents the chess board data, basically it's represention of FEN string;
 */
public class Board implements Serializable {
    private static final long serialVersionUID = 2194052829642412444L;

    public boolean bRedGo = true;
    public int rounds = 1;
    public float score = 0;
    static public int BOARD_PIECE_WIDTH = 9;
    static public int BOARD_PIECE_HEIGHT = 10;

    // do not access piece value directly, use getPieceByPosition instead
    // it's easy to make mistake with values of (x,y)
    private int[][] piece = new int[][]{
            {Piece.BJU, Piece.BMA, Piece.BXIANG, Piece.BSHI, Piece.BJIANG, Piece.BSHI, Piece.BXIANG, Piece.BMA, Piece.BJU},
            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, Piece.BPAO, 0, 0, 0, 0, 0, Piece.BPAO, 0},
            {Piece.BZU, 0, Piece.BZU, 0, Piece.BZU, 0, Piece.BZU, 0, Piece.BZU},
            {0, 0, 0, 0, 0, 0, 0, 0, 0},

            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {Piece.WBING, 0, Piece.WBING, 0, Piece.WBING, 0, Piece.WBING, 0, Piece.WBING},
            {0, Piece.WPAO, 0, 0, 0, 0, 0, Piece.WPAO, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {Piece.WJU, Piece.WMA, Piece.WXIANG, Piece.WSHI, Piece.WSHUAI, Piece.WSHI, Piece.WXIANG, Piece.WMA, Piece.WJU},
    };

    public static boolean isValidPosition(Position pos) {
        if(pos == null) return false;
        return pos.x >= 0 && pos.x < BOARD_PIECE_WIDTH && pos.y >= 0 && pos.y < BOARD_PIECE_HEIGHT;
    }

    public Board() {
        bRedGo = true;
        rounds = 1;
    }

    public Board(Board b) {
        this.bRedGo = b.bRedGo;
        this.rounds = b.rounds;
        this.score = b.score;
        for (int i = 0; i < this.piece.length; i++) {
            this.piece[i] = b.piece[i].clone();
        }
    }

    public void clear(){
        // clear all pieces to empty
        for (int y = 0; y < BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < BOARD_PIECE_WIDTH; x++) {
                piece[y][x] = Piece.EMPTY;
            }
        }
    }

    public boolean doMove(Move move){
        if(move == null) return false;

        Position start = move.fromPosition;
        Position end = move.toPosition;
        if(!isValidPosition(start) || !isValidPosition(end)) return false;

        int piece = getPieceByPosition(start);
        if (!Piece.isValid(piece)) {
            return false;
        }

        // move piece to end position
        setPieceByPosition(end, piece);
        // clear start position
        setPieceByPosition(start, Piece.EMPTY);
        bRedGo = !bRedGo;
        rounds++;
        return true;
    }

    public boolean doMoves(ArrayList<Move> moves){
        if(moves == null) return false;

        // clone one board, and do moves on the cloned board
        Board clonedBoard = new Board(this);
        for(Move move : moves){
            if(!clonedBoard.doMove(move)) return false;
        }

        // now it's ok to do moves on the real board
        for(Move move : moves){
            doMove(move);
        }
        return true;
    }

    public boolean doMoveFromString(String ucciString){
        Move move = new Move(this);
        if(!move.fromUCCIString(ucciString)) return false;
        return doMove(move);
    }

    public boolean doMovesFromUCCIStrings(ArrayList<String> ucciStrings){
        if(ucciStrings == null) return false;

        // clone one board, and do moves on the cloned board
        Board clonedBoard = new Board(this);

        // convert ucci strings to moves
        ArrayList<Move> moves = new ArrayList<>();
        for(String ucciString : ucciStrings){
            Move m = new Move(this);
            if(!m.fromUCCIString(ucciString)) return false;
            if(!clonedBoard.doMove(m)) return false;

            moves.add(m);
        }

        // now it's ok to do moves on the real board
        for(Move move : moves){
            doMove(move);
        }
        return true;
    }



    /*
    * Set piece value by position， it should not be called directly
     */
    public boolean setPieceByPosition(Position pos, int value){
        if (isValidPosition(pos)) {
            if(value == Piece.EMPTY || Piece.isValid(value)){
                piece[pos.y][pos.x] = value;
                return true;
            }
        }
        return false;
    }

    public boolean setPieceByPosition(int x, int y, int value) {
        if (x >= 0 && x < BOARD_PIECE_WIDTH && y >= 0 && y < BOARD_PIECE_HEIGHT) {
            if (value == Piece.EMPTY || Piece.isValid(value)) {
                piece[y][x] = value;
                return true;
            }
        }
        return false;
    }

    public int getPieceByPosition(Position pos) {
        if(isValidPosition(pos)){
            return piece[pos.y][pos.x];
        } else {
            return -1;
        }
    }

    public int getPieceByPosition(int x, int y) {
        if (x >= 0 && x < BOARD_PIECE_WIDTH && y >= 0 && y < BOARD_PIECE_HEIGHT) {
            return piece[y][x];
        } else {
            return -1;
        }
    }

    public String toFENString() {
        // https://www.xqbase.com/protocol/pgnfen2.htm
        // https://www.xqbase.com/protocol/cchess_fen.htm
        // 按白方视角，描述由上至下、由左至右的盘面，以/符号来分隔相邻横列。白方大写字母、黑方小写字母。
        // 棋盘的编号：从左到右为a-i，从下到上为0-9
        // FEN字符串中棋子的位置的顺序是从左到右，从上到下，空格用数字表示:
        // a9-i9, a8-i8, ..., a1-i1, a0-i0
        String fen = "";
        for (int y = 0; y < BOARD_PIECE_HEIGHT; y++) {
            String row = "";
            int zeros = 0;
            for (int x = 0; x < BOARD_PIECE_WIDTH; x++) {
                int piece = getPieceByPosition(x, y);
                if (piece != 0) {
                    // if zeros > 0, add zeros to fen
                    if (zeros > 0) {
                        row += zeros;
                        zeros = 0;
                    }
                    // convert piece to FEN
                    char fenPiece = Piece.pieceCharMap.get(piece);
                    row += (char) (fenPiece);
                } else {
                    zeros++;
                }
            }
            if (zeros > 0) {
                row += zeros;
            }
            fen += row;
            if (y < BOARD_PIECE_HEIGHT - 1) {
                // add / to separate rows
                fen += "/";
            }
        }
        String result = String.format("%s %s - - %s %s", fen, bRedGo ? "w" : "b", 0, rounds);
        return result;
    }

    public long getZobrist(boolean redGo) {
        return Zobrist.getZobristFromBoard(piece, redGo);
    }

    public boolean restoreFromFEN(String fenString) {
        if(fenString == null) return false;
        fenString = fenString.trim();

        // append missing parts
        if(fenString.endsWith("w") || fenString.endsWith("b")) {
            fenString += " - - 0 1";
        }

        // parse fen string
        String[] parts = fenString.split(" ");
        if (parts.length != 6) {
            // 改动：原库在这里打 android.util.Log。规则引擎一旦碰 android.*，JVM 单测里就得跑 Robolectric，
            // 而 FEN 解析失败本来只是「返回 false 让调用方换一条路」，日志没有参与判断，去掉不影响任何结果。
            return false;
        }

        // parse isRedGo
        String side = parts[1].toLowerCase();
        if(side.equals("w") || side.equals("r")) {
            // white or red, both are valid
            bRedGo = true;
        } else if(side.equals("b")) {
            bRedGo = false;
        } else {
            // 改动：同上，去掉 android.util.Log，保留原来的 return false。
            return false;
        }

        // parse rounds
        String part5 = parts[5];
        try {
            rounds = Integer.parseInt(part5);
        } catch (NumberFormatException e) {
            // 改动：同上，去掉 android.util.Log，回合数解析失败照旧当成整串 FEN 不可用。
            return false;
        }

        // parse fen string
        String fen = parts[0];
        int x = 0;
        int y = 0;
        for (int i = 0; i < fen.length(); i++) {
            char c = fen.charAt(i);
            if (c == '/') {
                // next row
                x = 0;
                y++;
            } else if (c >= '0' && c <= '9') {
                for (int k = 0; k < c - '0'; k++) {
                    piece[y][x] = 0;
                    x++;
                }
            } else {
                int value = Piece.pieceValueMap.get(c);
                piece[y][x] = value;
                x++;
            }
        }
        return true;
    }

    public void randomizePieces(){
        // randomize values for all pieces
        for (int y = 0; y < BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < BOARD_PIECE_WIDTH; x++) {
                piece[y][x] = (int) (Math.random() * 14);
            }
        }
    }
}
