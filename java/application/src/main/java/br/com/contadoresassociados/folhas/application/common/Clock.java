package br.com.contadoresassociados.folhas.application.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Relógio injetável; a data contábil usa o fuso de Brasília (pendência 3.7). */
public interface Clock {

    ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

    Instant now();

    default OffsetDateTime nowUtc() {
        return now().atOffset(ZoneOffset.UTC);
    }

    default LocalDate accountingDate() {
        return now().atZone(BRAZIL).toLocalDate();
    }

    static Clock system() {
        return Instant::now;
    }

    static Clock fixed(Instant instant) {
        return () -> instant;
    }
}
