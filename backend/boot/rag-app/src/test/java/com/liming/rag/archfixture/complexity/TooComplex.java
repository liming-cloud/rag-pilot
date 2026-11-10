package com.liming.rag.archfixture.complexity;

/** 违规样例：圈复杂度 11、可执行语句 31 条，各超上限 1，复杂度门禁要拦下。 */
public class TooComplex {

    int branches(int value) {
        if (value == 0) {
            return 0;
        }
        if (value == 1) {
            return 1;
        }
        if (value == 2) {
            return 2;
        }
        if (value == 3) {
            return 3;
        }
        if (value == 4) {
            return 4;
        }
        if (value == 5) {
            return 5;
        }
        if (value == 6) {
            return 6;
        }
        if (value == 7) {
            return 7;
        }
        if (value == 8) {
            return 8;
        }
        if (value == 9) {
            return 9;
        }
        return -1;
    }

    int statements() {
        int count = 0;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        count++;
        return count;
    }
}
