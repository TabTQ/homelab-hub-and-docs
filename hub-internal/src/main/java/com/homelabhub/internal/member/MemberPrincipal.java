package com.homelabhub.internal.member;

import java.io.Serializable;

/**
 * Who a member session belongs to. {@code stamp} is a short digest of the password hash the session was opened with: a
 * reset or a password change makes it differ, which ends the session on its next request (no session registry needed).
 */
public record MemberPrincipal(long personId, String name, String stamp) implements Serializable {}
