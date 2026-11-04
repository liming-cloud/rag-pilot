package com.liming.rag.identity.application.auth;

public record SignInResult(String token, Profile user) {
}
