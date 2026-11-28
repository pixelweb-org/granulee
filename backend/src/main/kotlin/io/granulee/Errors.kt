package io.granulee

/** What a request names is not there: a source, a table of the last scan, a trial. Answered with 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** The request cannot be carried out as it stands; the message says what to change. Answered with 400. */
class BadRequestException(message: String) : RuntimeException(message)

/** The request needs a signed-in user and has none: no session, or one that has run out. Answered with 401. */
class NotSignedInException(message: String = "Sign in first") : RuntimeException(message)

/** The signed-in user may not do this. Answered with 403. */
class ForbiddenException(message: String) : RuntimeException(message)
