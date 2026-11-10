package com.liming.rag.archfixture.complexity;

/** 合规样例：圈复杂度 10、可执行语句 30 条，正好在上限，复杂度门禁要放行。 */
public class WithinLimits {

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
        return count;
    }
}
