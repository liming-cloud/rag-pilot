package com.liming.rag.settings.api;

import java.util.List;

/**
 * 当前行业包里影响检索和回答的内容（D-045），部署时选定、启动后不变；界面上只读。
 *
 * @param code 行业包的名字，即 {@code industry-packs/<code>/} 目录名
 * @param name 显示名称
 * @param answerRole 回答提示词开头的角色
 * @param citationExample 回答提示词里引用格式的示例句，带 [2] 这样的引用
 * @param rewriteRole 低分改写提示词开头的角色
 * @param rewriteHints 低分改写的行业提示，排在内核规则之间
 * @param vocabulary 行业词表：问题里出现下位词或同义词时，检索前追加上位词
 */
public record IndustryPack(String code, String name, String answerRole, String citationExample, String rewriteRole,
        List<String> rewriteHints, List<Term> vocabulary) {

    public IndustryPack {
        rewriteHints = rewriteHints == null ? List.of() : List.copyOf(rewriteHints);
        vocabulary = vocabulary == null ? List.of() : List.copyOf(vocabulary);
    }

    /**
     * 词表的一组。
     *
     * @param term 上位词，即资料里的规范说法
     * @param members 下位词和同义词，即员工常用的说法
     */
    public record Term(String term, List<String> members) {

        public Term {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }
}
