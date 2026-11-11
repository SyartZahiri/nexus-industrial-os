package io.nexus.app.web;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.state.IllegalTransitionException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error format for every controller: RFC 7807 problem JSON.
 * Only business exceptions of the core pass their message to the client; everything else gets a fixed text
 * and is logged, so internal details never leave the server.
 */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(DomainException.class)
  ProblemDetail domain(DomainException e) {
    HttpStatus status = switch (e.kind()) {
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case INVALID -> HttpStatus.BAD_REQUEST;
      case CONFLICT -> HttpStatus.CONFLICT;
    };
    return ProblemDetail.forStatusAndDetail(status, e.getMessage());
  }

  @ExceptionHandler(IllegalTransitionException.class)
  ProblemDetail illegalTransition(IllegalTransitionException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ProblemDetail versionConflict(OptimisticLockingFailureException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
        "Das Objekt wurde zwischenzeitlich geändert. Bitte neu laden und erneut versuchen.");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail dataRule(DataIntegrityViolationException e) {
    log.warn("Datenregel verletzt", e);
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Die Änderung verletzt eine Datenregel.");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    log.error("Unerwarteter Fehler", e);
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Interner Fehler");
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                HttpStatusCode status, WebRequest request) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Eingabe ungültig");
    List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))).toList();
    body.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(body);
  }
}
