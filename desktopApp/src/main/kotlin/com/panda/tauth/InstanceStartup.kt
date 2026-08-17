package com.panda.tauth

// A launch whose show request a running instance acknowledged is finished: it opens no window, no
// session and no vault, and its exit status is the 0 of a main that returns.
fun startUnlessSuperseded(role: InstanceRole, start: (InstanceRole) -> Unit) {
    if (role is InstanceRole.Superseded) return
    start(role)
}
