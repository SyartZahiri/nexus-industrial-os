package io.nexus.app.web;

public class NotFoundException extends RuntimeException {
  public NotFoundException(String what, String id) {
    super(what + " " + id + " nicht gefunden");
  }
}
