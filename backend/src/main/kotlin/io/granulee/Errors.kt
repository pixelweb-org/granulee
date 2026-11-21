package io.granulee

/** What a request names is not there: a source, a table of the last scan, a trial. Answered with 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** The request cannot be carried out as it stands; the message says what to change. Answered with 400. */
class BadRequestException(message: String) : RuntimeException(message)
