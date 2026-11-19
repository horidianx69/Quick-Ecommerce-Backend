package com.quickcommerce.dispatch;

/** What admin sees in the driver list: the driver joined with their login. */
public record AdminDriverView(Long userId, String name, String email, String phone, Driver.Status status, boolean online) {}
