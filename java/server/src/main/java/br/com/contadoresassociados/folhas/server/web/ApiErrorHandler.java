package br.com.contadoresassociados.folhas.server.web;

import br.com.contadoresassociados.folhas.application.clients.CatalogException;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.server.db.Db;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Traduz exceções em respostas ProblemDetails com código estável e texto em português. */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);
    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest request) {
        return respond(request, e.status(), e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<Map<String, Object>> catalog(CatalogException e, HttpServletRequest request) {
        var status = switch (e.code()) {
            case "catalog.concurrency" -> 409;
            case "catalog.not_found" -> 404;
            default -> 400;
        };
        return respond(request, status, e.code(), e.getMessage(), List.of());
    }

    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<Map<String, Object>> domain(DomainValidationException e, HttpServletRequest request) {
        return respond(request, 400, e.code(), e.getMessage(), List.of());
    }

    @ExceptionHandler(Db.DatabaseException.class)
    public ResponseEntity<Map<String, Object>> database(Db.DatabaseException e, HttpServletRequest request) {
        if (e.isUniqueViolation()) {
            return respond(request, 400, "catalog.duplicate",
                    "Já existe outro cadastro nesta organização com o mesmo valor único (CPF, CNPJ, código ou nome).",
                    List.of());
        }
        if (e.isForeignKeyViolation()) {
            return respond(request, 400, "catalog.reference_invalid",
                    "O registro referencia um item inexistente ou arquivado.", List.of());
        }
        LOG.error("Falha de banco (SQLSTATE {}) na requisição {}.", e.sqlState(), RequestContext.correlationId(request), e);
        return respond(request, 503, "database_unavailable",
                "O banco central não respondeu. Tente novamente em instantes.", List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception e, HttpServletRequest request) {
        return respond(request, 400, "request.invalid", "A solicitação está incompleta ou em formato inválido.",
                List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(Exception e, HttpServletRequest request) {
        return respond(request, 405, "request.method_not_allowed", "Método não suportado.", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType(Exception e, HttpServletRequest request) {
        return respond(request, 415, "request.media_type", "Envie o conteúdo como application/json.", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(Exception e, HttpServletRequest request) {
        return respond(request, 404, "not_found", "Recurso não encontrado.", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        LOG.error("Erro inesperado na requisição {}.", RequestContext.correlationId(request), e);
        return respond(request, 500, "internal_error", "Ocorreu um erro inesperado. Informe o código de correlação "
                + "ao suporte.", List.of());
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpServletRequest request, int status, String code,
            String detail, List<String> details) {
        return ResponseEntity.status(status).contentType(PROBLEM)
                .body(Problems.body(request, status, code, Problems.title(status), detail, details));
    }
}
