# bugger-it

A Clojure facility for using the Java Debug Interface (JDI), part of the Java
Platform Debugger Architecture (JPDA), to debug a program running in a separate
JVM process.

JDI is a powerful interface that allows you to do a lot of things with the JVM
you're debugging. Most debuggers use that power to provide fairly standard
capabilities: hit a breakpoint, show you the locals, allow you to manually step
through code as it executes, etc. But typically they don't give you much ability
to programmatically manage the debugger.

The goal of `bugger-it` is to give you convenient programmatic control over the
debugging facilities that JDI provides. You can use that to hit a breakpoint and
manually step through code if you want. Or you can write a function that
`bugger-it` will invoke every time your breakpoint is hit, which can gather
whatever information you want gathered at the breakpoint, do something useful
with it, and automatically resume code execution in JVM being debugged.

That 'do something with it' could just be emitting trace-like logging, or it
could be profiling, or it could be conditional installation/disabling of other
breakpoints, or state inspection, or hitting up your favourite HTTP interface to
order you a pizza online. I don't care. `bugger-it`'s job is to allow you to
install a breakpoint, and to invoke your callback handler function when JDI
tells us your breakpoint got hit.

I've drawn a lot of inspiration from
[Hugo Duncan's ritz](https://github.com/pallet/ritz) project. `ritz`'s intent
is to utilise JDI specifically to provide an interface for an nREPL middleware
to add debugging facilities to editors such as `emacs`. As a result, `ritz`'s
focus is more on managing the JVM and the debug interface for you. You install
a breakpoint, and then when it's hit, `ritz` calls a `defmulti` which does what
`ritz` does when breakpoints are hit. You could handle it yourself by installing
your own `defmethod`, but I don't think that's really the point of `ritz`.

The focus for `bugger-it` at this stage is more on the user sitting at a REPL
wanting to programmatically observe the execution of their (or someone else's)
code in the remote VM.


## Installation

Available from https://github.com/samroberton/bugger-it.


## Usage

At this stage, `bugger-it` is intended for connecting to an already-running JVM
process. Therefore the JVM process will need to have been started with these
options:
    -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n

If you want to be able to inspect local variables in the debuggee, and you're
not too worried about running out of memory from lazy seqs holding onto their
heads, you'll probably want to start your debuggee JVM process with the
following, as well:
    -Dclojure.compiler.disable-locals-clearing=true

If you wish to debug a process that you run from a `lein` REPL, for example, you
can launch the REPL from your debuggee's `lein` project directory like this:
```bash
    $ JAVA_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n" \
          -Dclojure.compiler.disable-locals-clearing=true lein repl
```

When that JVM starts up, you'll see it report the port number to which it's
bound its debug listener socket, like so:
    Listening for transport dt_socket at address: 34617

Then, in a REPL with `bugger-it` on the classpath and referred as `bugger`:
```clojure
    (def debuggee (bugger/connect-to-vm "localhost" 34617))
```
with whatever hostname and port number are appropriate. (`connect-to-vm` returns
a `Debuggee` handle for the remote VM which you'll need to do anything useful,
so in the above we put into a `var` we can use in our REPL session.)

Once you're connected, you can install breakpoints like so:
```clojure
(defn my-handler-function [thread]
  (println "Hooray, my breakpoint got hit in thread " thread))

(bugger/break-at-point debuggee
                       my-handler-fn
                       :bugger-it-examples.fibonacci/stupid-fib
                       7
                       {:suspend :none})
```

Then, when your breakpoint is hit, the remote VM will continue executing and
`my-handler-function` will be invoked in your REPL session's VM.


## Examples

...


### Bugs

No doubt legions of them.  Feel free to report them via github.


## License

Copyright © 2014 Sam Roberton

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
