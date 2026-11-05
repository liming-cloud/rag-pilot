package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.Validity;
import java.time.LocalDate;

/** 按哪天判断有效期：今天，以及“即将到期”截止到哪天（含），口径同 {@link Validity#state}。 */
public record Window(LocalDate today, LocalDate warnUntil) {
}
