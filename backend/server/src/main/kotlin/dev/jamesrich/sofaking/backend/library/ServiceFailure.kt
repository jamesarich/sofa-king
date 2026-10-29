package dev.jamesrich.sofaking.backend.library

/** A failed call to an outside service; [code] is a short class for job notes and errors, with no URL or secret. */
open class ServiceFailure(val code: String) : RuntimeException(code)
