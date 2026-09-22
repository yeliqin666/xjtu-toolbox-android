/*
    DroidFish - An Android chess program.
    Copyright (C) 2011  Peter Österlund, peterosterlund2@gmail.com
    Copyright (C) 2012  Leo Mayer

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.xjtu.toolbox.game.xiangqi.rules;

import java.util.HashMap;

/** Constants for different piece types.
 * https://www.xqbase.com/protocol/cchess_move.htm
 *
 * 白方(红色)棋子以大写字母表示，黑方棋子以小写字母表示
 * 红方以大写字元来表达兵种, PABNCRK分别代表兵、仕、相、马、炮、车、帅
 * 黑方以小写字元表达,      pabncrk分别代表卒、士、象、马、炮、车、将
 * */
public class Piece {
    public static final int EMPTY = 0;
    public static final Character EMPTY_CHAR = ' ';

    public static final int WSHUAI = 1; // K, 帅
    public static final int WSHI = 2; // A, 仕
    public static final int WXIANG = 3; // B, 相
    public static final int WMA = 4; // N, 马
    public static final int WJU = 5; // R, 车
    public static final int WPAO = 6; // C, 炮
    public static final int WBING = 7; // P, 兵

    public static final int BJIANG = 8; // k, 将
    public static final int BSHI = 9; // a, 士
    public static final int BXIANG = 10; // b, 象
    public static final int BMA = 11; // n, 马
    public static final int BJU = 12;   // r, 车
    public static final int BPAO = 13;  // c, 炮
    public static final int BZU = 14;   // p, 卒

    public static final int nPieceTypes = 15;

    public static final HashMap<Integer, Character> pieceCharMap = new HashMap<>();
    static {
        pieceCharMap.put(WSHUAI, 'K');
        pieceCharMap.put(WSHI, 'A');
        pieceCharMap.put(WXIANG, 'B');
        pieceCharMap.put(WMA, 'N');
        pieceCharMap.put(WJU, 'R');
        pieceCharMap.put(WPAO, 'C');
        pieceCharMap.put(WBING, 'P');

        pieceCharMap.put(BJIANG, 'k');
        pieceCharMap.put(BSHI, 'a');
        pieceCharMap.put(BXIANG, 'b');
        pieceCharMap.put(BMA, 'n');
        pieceCharMap.put(BJU, 'r');
        pieceCharMap.put(BPAO, 'c');
        pieceCharMap.put(BZU, 'p');
    }

    public static final HashMap<Integer, Character> pieceNameMap = new HashMap<>();
    static {
        pieceNameMap.put(WSHUAI, '帅');
        pieceNameMap.put(WSHI, '仕');
        pieceNameMap.put(WXIANG, '相');
        pieceNameMap.put(WMA, '马');
        pieceNameMap.put(WJU, '车');
        pieceNameMap.put(WPAO, '炮');
        pieceNameMap.put(WBING, '兵');

        pieceNameMap.put(BJIANG, '将');
        pieceNameMap.put(BSHI, '士');
        pieceNameMap.put(BXIANG, '象');
        pieceNameMap.put(BMA, '马');
        pieceNameMap.put(BJU, '车');
        pieceNameMap.put(BPAO, '炮');
        pieceNameMap.put(BZU, '卒');
    }

    public static final HashMap<Character, Integer> pieceValueMap = new HashMap<>();
    static {
        pieceValueMap.put('K', WSHUAI);
        pieceValueMap.put('A', WSHI);
        pieceValueMap.put('B', WXIANG);
        pieceValueMap.put('N', WMA);
        pieceValueMap.put('R', WJU);
        pieceValueMap.put('C', WPAO);
        pieceValueMap.put('P', WBING);

        pieceValueMap.put('k', BJIANG);
        pieceValueMap.put('a', BSHI);
        pieceValueMap.put('b', BXIANG);
        pieceValueMap.put('n', BMA);
        pieceValueMap.put('r', BJU);
        pieceValueMap.put('c', BPAO);
        pieceValueMap.put('p', BZU);
    }

    /**
     * Return true if p is a white piece, false otherwise.
     * Note that if p is EMPTY, an unspecified value is returned.
     */
    public static boolean isRed(int pType) { return pType <= WBING && pType >= WSHUAI; }
    public static boolean isBlack(int pType) {
        return pType <= BZU && pType >= BJIANG;
    }
    public static boolean isValid(int pType) {
        return pType <= BZU && pType >= WSHUAI;
    }
    public static boolean isDiagonalPiece(int pType) {
        return pType == WXIANG || pType == BXIANG || pType == WSHI || pType == BSHI || pType == WMA || pType == BMA;
    }

    public static int swapColor(int pType) {
        if (pType == EMPTY)
            return EMPTY;
        return isRed(pType) ? pType + (BZU - WBING) : pType - (BZU - WBING);
    }

    // Return piece byte value by piece type
    static public Character getCharByValue(int i){
        // find in pieceByteMap
        if(pieceCharMap.containsKey(i)){
            return pieceCharMap.get(i);
        } else {
            return EMPTY_CHAR;
        }
    }

    // return piece name by piece value
    static public Character getNameByValue(int i){
        // find in pieceNameMap
        if(pieceNameMap.containsKey(i)){
            return pieceNameMap.get(i);
        } else {
            return EMPTY_CHAR;
        }
    }

    // return piece value by piece byte
    static public int getValueByChar(char b){
        // find in pieceValueMap
        if(pieceValueMap.containsKey(b)){
            return pieceValueMap.get(b);
        } else {
            return EMPTY;
        }
    }
}
