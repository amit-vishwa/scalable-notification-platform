# Scalable Notification Platform

A production-oriented backend platform for accepting and asynchronously
processing email notification requests.

## Current status

The project is being developed as a modular monolith using Java 17,
Spring Boot, Maven, MySQL, Flyway, and Spring Data JPA.

## Core objectives

- Accept notification requests through authenticated REST APIs.
- Prevent duplicate notifications using source application and idempotency key.
- Persist notifications before returning `202 Accepted`.
- Process pending notifications asynchronously.
- Retry temporary delivery failures.
- Track notification and delivery status in MySQL.
- Handle failures gracefully and provide operational health endpoints.

## Architecture

The initial implementation uses a single deployable Spring Boot application
organized into modular packages. Separate worker services, Kafka, and
Kubernetes deployment are planned for later phases.

## Prerequisites

- Java 17
- Git 2.45 or newer
- Maven Wrapper included with the repository
- MySQL 8.x for database-related development
- Docker Desktop is optional and should not be started for bootstrap testing

## Build and test

On Windows PowerShell:

```powershell
.\mvnw.cmd clean test