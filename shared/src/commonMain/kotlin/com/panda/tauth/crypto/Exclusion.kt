package com.panda.tauth.crypto

// Mutual exclusion over the state the token stands for. The common stdlib has atomics but no lock.
internal expect fun <T> exclusively(token: Any, block: () -> T): T
