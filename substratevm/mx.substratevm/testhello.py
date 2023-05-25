# Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2020, 2020, Red Hat Inc. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# ----------------------------------------------------------------------------------------------------
# pylint: skip-file
#
# A test script for use from gdb. It can be used to drive execution of
# a native image version of test app Hello and check that the debug
# info is valid.
#
# Assumes you have already executed
#
# $ javac hello/Hello.java
# $ mx native-image -g hello.hello
#
# Run test
#
# gdb -x testhello.py /path/to/hello
#
# exit status 0 means all is well 1 means test failed
#
# n.b. assumes the sourcefile cache is in local dir sources

import re
import sys
import os

# A helper class which checks that a sequence of lines of output
# from a gdb command matches a sequence of per-line regular
# expressions

class Checker:
    # Create a checker to check gdb command output text.
    # name - string to help identify the check if we have a failure.
    # regexps - a list of regular expressions which must match.
    # successive lines of checked
    def __init__(self, name, regexps):
        self.name = name
        if not isinstance(regexps, list):
            regexps = [regexps]
        self.rexps = [re.compile(regexp) for regexp in regexps]

    # Check that successive lines of a gdb command's output text
    # match the corresponding regexp patterns provided when this
    # Checker was created.
    # text - the full output of a gdb comand run by calling
    # gdb.execute and passing to_string = True.
    # Exits with status 1 if there are less lines in the text
    # than regexp patterns or if any line fails to match the
    # corresponding pattern otherwise prints the text and returns
    # the set of matches.
    def check(self, text, skip_fails=True):
        lines = text.splitlines()
        rexps = self.rexps
        num_lines = len(lines)
        num_rexps = len(rexps)
        line_idx = 0
        matches = []
        for i in range(0, (num_rexps)):
            rexp = rexps[i]
            match = None
            while line_idx < num_lines and match is None:
                line = lines[line_idx]
                match = rexp.match(line)
                if  match is None:
                    if not skip_fails:
                        print('Checker %s: match %d failed at line %d %s\n'%(self.name, i, line_idx, line))
                        print(self)
                        print(text)
                        sys.exit(1)
                else:
                    matches.append(match)
                line_idx += 1
        if len(matches) < num_rexps:
            print('Checker %s: insufficient matching lines %d for regular expressions %d'%(self.name, len(matches), num_rexps))
            print(self)
            print(text)
            sys.exit(1)
        print(text)
        return matches

    # Format a Checker as a string
    def __str__(self):
        rexps = self.rexps
        result = 'Checker %s '%(self.name)
        result += '{\n'
        for rexp in rexps:
            result += '  %s\n'%(rexp)
        result += '}\n'
        return result

def execute(command):
    print('(gdb) %s'%(command))
    return gdb.execute(command, to_string=True)

# Configure this gdb session

# ensure file listings show only the current line
execute("set listsize 1")

# Start of actual test code
#

def test():

    # define some useful patterns
    address_pattern = '0x[0-9a-f]+'
    spaces_pattern = '[ \t]+'
    maybe_spaces_pattern = '[ \t]*'
    digits_pattern = '[0-9]+'
    package_pattern = '[a-z/]+'
    package_file_pattern = '[a-zA-Z0-9_/]+\\.java'
    varname_pattern = '[a-zA-Z0-9_]+'
    wildcard_pattern = '.*'
    # obtain the gdb version
    # n.b. we can only test printing in gdb 10.1 upwards
    exec_string=execute("show version")
    checker = Checker('show version',
                      r"GNU gdb %s (%s)\.(%s)%s"%(wildcard_pattern, digits_pattern, digits_pattern, wildcard_pattern))
    matches = checker.check(exec_string, skip_fails=False)
    # n.b. can only get back here with one match
    match = matches[0]
    major = int(match.group(1))
    minor = int(match.group(2))
    # printing object data requires a patched gdb
    # once the patch is in we can check for a suitable
    # range of major.minor versions
    # for now we use an env setting
    print("Found gdb version %s.%s"%(major, minor))
    # can_print_data = major > 10 or (major == 10 and minor > 1)
    can_print_data = False
    if os.environ.get('GDB_CAN_PRINT', '') == 'True':
        can_print_data = True

    isolates = False
    if os.environ.get('debuginfotest.isolates', 'no') == 'yes':
        isolates = True
        
    if isolates:
        print("Testing with isolates enabled!")
    else:
        print("Testing with isolates disabled!")

    if not can_print_data:
        print("Warning: cannot test printing of objects!")

    # disable prompting to continue output
    execute("set pagination off")
    # enable pretty printing of structures
    execute("set print pretty on")
    # set a break point at hello.Hello::main
    # expect "Breakpoint 1 at 0x[0-9a-f]+: file hello.Hello.java, line 67."
    exec_string = execute("break hello.Hello::main")
    rexp = r"Breakpoint 1 at %s: file hello/Hello\.java, line 67\."%address_pattern
    checker = Checker('break main', rexp)
    checker.check(exec_string)

    # run the program
    execute("run")

    # list the line at the breakpoint
    # expect "67	        Greeter greeter = Greeter.greeter(args);"
    exec_string = execute("list")
    checker = Checker(r"list bp 1", "67%sGreeter greeter = Greeter\.greeter\(args\);"%spaces_pattern)
    checker.check(exec_string, skip_fails=False)

    # run a backtrace
    # expect "#0  hello.Hello.main(java.lang.String[] *).* at hello.Hello.java:67"
    # expect "#1  0x[0-9a-f]+ in com.oracle.svm.core.code.IsolateEnterStub.JavaMainWrapper_run_.* at [a-z/]+/JavaMainWrapper.java:[0-9]+"
    exec_string = execute("backtrace")
    checker = Checker("backtrace hello.Hello::main",
                      [r"#0%shello\.Hello::main\(java\.lang\.String\[\] \*\)%s at hello/Hello\.java:67"%(spaces_pattern, wildcard_pattern),
                       r"#1%s%s in com\.oracle\.svm\.core\.code\.IsolateEnterStub::JavaMainWrapper_run_%s at %sJavaMainWrapper\.java:[0-9]+"%(spaces_pattern, address_pattern, wildcard_pattern, package_pattern)
                       ])
    checker.check(exec_string, skip_fails=False)

    if can_print_data:
        # print the contents of the arguments array which will be in rdi
        exec_string = execute("print /x *(('java.lang.String[]' *)$rdi)")
        rexp = [r"%s = {"%(wildcard_pattern),
                r"%s<java.lang.Object> = {"%(spaces_pattern),
                r"%s<_objhdr> = {"%(spaces_pattern),
                r"%shub = %s,"%(spaces_pattern, address_pattern),
                r"%sidHash = %s"%(spaces_pattern, address_pattern),
                r"%s}, <No data fields>}, "%(spaces_pattern),
                r"%smembers of java\.lang\.String\[\]:"%(spaces_pattern),
                r"%slen = 0x0,"%(spaces_pattern),
                r"%sdata = %s"%(spaces_pattern, address_pattern),
                "}"]

        checker = Checker("print String[] args", rexp)

        checker.check(exec_string, skip_fails=False)

        # print the hub of the array and check it has a name field
        exec_string = execute("print /x *(('java.lang.String[]' *)$rdi)->hub")
        if isolates:
            rexp = [r"%s = {"%(wildcard_pattern),
                    r"%s<java.lang.Class> = {"%(spaces_pattern),
                    r"%s<java.lang.Object> = {"%(spaces_pattern),
                    r"%s<_objhdr> = {"%(spaces_pattern),
                    r"%shub = %s,"%(spaces_pattern, address_pattern),
                    r"%sidHash = %s"%(spaces_pattern, address_pattern),
                    r"%s}, <No data fields>},"%(spaces_pattern),
                    r"%smembers of java\.lang\.Class:"%(spaces_pattern),
                    r"%sname = %s,"%(spaces_pattern, address_pattern),
                    r"%s}, <No data fields>}"%spaces_pattern]
        else:
            rexp = [r"%s = {"%(wildcard_pattern),
                    r"%s<java.lang.Object> = {"%(spaces_pattern),
                    r"%s<_objhdr> = {"%(spaces_pattern),
                    r"%shub = %s,"%(spaces_pattern, address_pattern),
                    r"%sidHash = %s"%(spaces_pattern, address_pattern),
                    r"%s}, <No data fields>},"%(spaces_pattern),
                    r"%smembers of java\.lang\.Class:"%(spaces_pattern),
                    r"%sname = %s,"%(spaces_pattern, address_pattern),
                    "}"]

        checker = Checker("print String[] hub", rexp)

        checker.check(exec_string, skip_fails=True)

        # print the hub name field and check it is String[]
        # n.b. the expected String text is not necessarily null terminated
        # so we need a wild card before the final quote
        exec_string = execute("x/s (('java.lang.String[]' *)$rdi)->hub->name->value->data")
        checker = Checker("print String[] hub name",
                          r"%s:%s\"\[Ljava.lang.String;%s\""%(address_pattern, spaces_pattern, wildcard_pattern))
        checker.check(exec_string, skip_fails=False)

        # ensure we can reference static fields
        exec_string = execute("print 'java.math.BigDecimal'::BIG_TEN_POWERS_TABLE")
        if isolates:
            rexp = r"%s = \(_z_\.java.math.BigInteger\[\] \*\) %s"%(wildcard_pattern, address_pattern)
        else:
            rexp = r"%s = \(java.math.BigInteger\[\] \*\) %s"%(wildcard_pattern, address_pattern)

        checker = Checker("print static field value",rexp)
        checker.check(exec_string, skip_fails=False)

        # ensure we can dereference static fields
        exec_string = execute("print 'java.math.BigDecimal'::BIG_TEN_POWERS_TABLE->data[3]->mag->data[0]")
        checker = Checker("print static field value contents",
                          r"%s = 1000"%(wildcard_pattern))
        checker.check(exec_string, skip_fails=False)

    # look up PrintStream.println methods
    # expect "All functions matching regular expression "java.io.PrintStream.println":"
    # expect ""
    # expect "File java.base/java/io/PrintStream.java:"
    # expect "      void java.io.PrintStream::println(java.lang.Object);"
    # expect "      void java.io.PrintStream::println(java.lang.String);"
    exec_string = execute("info func java.io.PrintStream::println")
    #    checker = Checker("info func java.io.PrintStream::println",
    #                      ["All functions matching regular expression \"java\\.io\\.PrintStream::println\":",
    #                       "",
    #                       "File .*java/io/PrintStream.java:",
    #                       "[ \t]*void java.io.PrintStream::println\\(java\\.lang\\.Object \\*\\);",
    #                       "[ \t]*void java.io.PrintStream::println\\(java\\.lang\\.String \\*\\);",
    #                      ])
    rexp = r"%svoid java.io.PrintStream::println\(java\.lang\.String \*\)"%maybe_spaces_pattern
    checker = Checker("info func java.io.PrintStream::println", rexp)
    checker.check(exec_string)

    # set a break point at PrintStream.println(String)
    # expect "Breakpoint 2 at 0x[0-9a-f]+: java.base/java/io/PrintStream.java, line [0-9]+."
    exec_string = execute("break java.io.PrintStream::println(java.lang.String *)")
    rexp = r"Breakpoint 2 at %s: file .*java/io/PrintStream\.java, line %s\."%(address_pattern, digits_pattern)
    checker = Checker('break println', rexp)
    checker.check(exec_string, skip_fails=False)

    # step into method call
    execute("step")

    # list current line
    # expect "34	            if (args.length == 0) {"
    exec_string = execute("list")
    rexp = r"34%sif \(args\.length == 0\) {"%spaces_pattern
    checker = Checker('list hello.Hello$Greeter.greeter', rexp)
    checker.check(exec_string, skip_fails=False)

    # print details of greeter types
    exec_string = execute("ptype 'hello.Hello$NamedGreeter'")
    if isolates:
        rexp = [r"type = class hello\.Hello\$NamedGreeter : public hello\.Hello\$Greeter {",
                r"%sprivate:"%(spaces_pattern),
                r"%s_z_\.java\.lang\.String \*name;"%(spaces_pattern),
                r"",
                r"%spublic:"%(spaces_pattern),
                r"%svoid greet\(void\);"%(spaces_pattern),
                r"}"]
    else:
        rexp = [r"type = class hello\.Hello\$NamedGreeter : public hello\.Hello\$Greeter {",
                r"%sprivate:"%(spaces_pattern),
                r"%sjava\.lang\.String \*name;"%(spaces_pattern),
                r"",
                r"%spublic:"%(spaces_pattern),
                r"%svoid greet\(void\);"%(spaces_pattern),
                r"}"]

    checker = Checker('ptype NamedGreeter', rexp)
    checker.check(exec_string, skip_fails=False)

    exec_string = execute("ptype 'hello.Hello$Greeter'")
    rexp = [r"type = class hello\.Hello\$Greeter : public java\.lang\.Object {",
            r"%spublic:"%(spaces_pattern),
            r"%sstatic hello\.Hello\$Greeter \* greeter\(java\.lang\.String\[\] \*\);"%(spaces_pattern),
            r"}"]

    checker = Checker('ptype Greeter', rexp)
    checker.check(exec_string, skip_fails=False)

    exec_string = execute("ptype 'java.lang.Object'")
    rexp = [r"type = class java\.lang\.Object : public _objhdr {",
            r"%spublic:"%(spaces_pattern),
            r"%svoid Object\(void\);"%(spaces_pattern),
            r"%sboolean equals\(java\.lang\.Object \*\);"%(spaces_pattern),
            r"%sprivate:"%(spaces_pattern),
            r"%sint hashCode\(void\);"%(spaces_pattern),
            r"%sjava\.lang\.String \* toString\(void\);"%(spaces_pattern),
            r"}"]
    
    checker = Checker('ptype Object', rexp)
    checker.check(exec_string, skip_fails=True)

    exec_string = execute("ptype _objhdr")
    if isolates:
        rexp = [r"type = struct _objhdr {",
                r"%s_z_\.java\.lang\.Class \*hub;"%(spaces_pattern),
                r"%sint idHash;"%(spaces_pattern),
                r"}"]
    else:
        rexp = [r"type = struct _objhdr {",
                r"%sjava\.lang\.Class \*hub;"%(spaces_pattern),
                r"%sint idHash;"%(spaces_pattern),
                r"}"]

    checker = Checker('ptype _objhdr', rexp)
    checker.check(exec_string, skip_fails=True)

    exec_string = execute("ptype 'java.lang.String[]'")
    if isolates:
        rexp = [r"type = class java.lang.String\[\] : public java.lang.Object {",
                r"%sint len;"%(spaces_pattern),
                r"%s_z_\.java\.lang\.String \*data\[0\];"%(spaces_pattern),
                r"}"]
    else:
        rexp = [r"type = class java.lang.String\[\] : public java.lang.Object {",
                r"%sint len;"%(spaces_pattern),
                r"%sjava\.lang\.String \*data\[0\];"%(spaces_pattern),
                r"}"]

    checker = Checker('ptype String[]', rexp)
    checker.check(exec_string, skip_fails=True)

    # run a backtrace
    # expect "#0  _hello.Hello$Greeter::greeter(java.lang.String[]).* at hello.Hello.java:34"
    # expect "#1  0x[0-9a-f]+ in _hello.Hello::main(java.lang.String[]).* at hello.Hello.java:67"
    # expect "#2  0x[0-9a-f]+ in _com.oracle.svm.core.code.IsolateEnterStub.JavaMainWrapper_run_.* at [a-z/]+/JavaMainWrapper.java:[0-9]+"
    exec_string = execute("backtrace")
    checker = Checker("backtrace hello.Hello.Greeter::greeter",
                      [r"#0%shello\.Hello\$Greeter::greeter\(java\.lang\.String\[\] \*\)%s at hello/Hello\.java:34"%(spaces_pattern, wildcard_pattern),
                       r"#1%s%s in hello\.Hello::main\(java\.lang\.String\[\] \*\)%s at hello/Hello\.java:67"%(spaces_pattern, address_pattern, wildcard_pattern),
                       r"#2%s%s in com\.oracle\.svm\.core\.code\.IsolateEnterStub::JavaMainWrapper_run_%s at [a-z/]+/JavaMainWrapper\.java:%s"%(spaces_pattern, address_pattern, wildcard_pattern, digits_pattern)
                       ])
    checker.check(exec_string, skip_fails=False)

    # now step into inlined code
    execute("next")

    # check we are still in hello.Hello$Greeter.greeter but no longer in hello.Hello.java
    exec_string = execute("backtrace 1")
    checker = Checker("backtrace inline",
                      [r"#0%shello\.Hello\$Greeter::greeter\(java\.lang\.String\[\] \*\)%s at (%s):%s"%(spaces_pattern, wildcard_pattern, package_file_pattern, digits_pattern)])
    matches = checker.check(exec_string, skip_fails=False)
    # n.b. can only get back here with one match
    match = matches[0]
    if match.group(1) == "hello.Hello.java":
        line = exec_string.replace("\n", "")
        print('bad match for output %d\n'%(line))
        print(checker)
        sys.exit(1)

    # continue to next breakpoint
    execute("continue")

    # run backtrace to check we are in java.io.PrintStream::println(java.lang.String)
    # expect "#0  java.io.PrintStream::println(java.lang.String).* at java.base/java/io/PrintStream.java:[0-9]+"
    exec_string = execute("backtrace 1")
    checker = Checker("backtrace 1 PrintStream::println",
                      [r"#0%sjava\.io\.PrintStream::println\(java\.lang\.String \*\)%s at %sjava/io/PrintStream.java:%s"%(spaces_pattern, wildcard_pattern, wildcard_pattern, digits_pattern)])
    checker.check(exec_string, skip_fails=False)

    # list current line
    # expect "[0-9]+        synchronized (this) {"
    exec_string = execute("list")
    rexp = r"(%s)%ssynchronized \(this\) {"%(digits_pattern, spaces_pattern)
    checker = Checker('list println 1', rexp)
    matches = checker.check(exec_string, skip_fails=False)

    # n.b. can only get back here with one match
    match = matches[0]
    prev_line_num = int(match.group(1)) - 1

    # check the previous line is the declaration for println(String)
    # list {prev_line_num}
    # expect "{prev_line_num}        public void println(String [a-zA-Z0-9_]+) {"
    exec_string = execute("list %d"%prev_line_num)
    rexp = r"%d%spublic void println\(String %s\) {"%(prev_line_num, spaces_pattern, varname_pattern)
    checker = Checker('list println 2', rexp)
    checker.check(exec_string, skip_fails=False)

    if can_print_data:
        # print the java.io.PrintStream instance and check its type
        exec_string = execute("print /x *(('java.io.PrintStream' *)$rdi)")
        rexp = [r"%s = {"%(wildcard_pattern),
                r"%s<java.io.FilterOutputStream> = {"%(spaces_pattern),
                r"%s<java.io.OutputStream> = {"%(spaces_pattern),
                r"%s<java.lang.Object> = {"%(spaces_pattern),
                r"%s<_objhdr> = {"%(spaces_pattern),
                r"%shub = %s,"%(spaces_pattern, address_pattern),
                r"%sidHash = %s"%(spaces_pattern, address_pattern),
                r"%s}, <No data fields>}, <No data fields>},"%(spaces_pattern),
                r"%smembers of java.io.FilterOutputStream:"%(spaces_pattern),
                r"%sclosed = 0x0,"%(spaces_pattern),
                r"%sout = %s,"%(spaces_pattern, address_pattern),
                r"%scloseLock = %s"%(spaces_pattern, address_pattern),
                r"%s},"%(spaces_pattern),
                r"%smembers of java.io.PrintStream:"%(spaces_pattern),
                r"%stextOut = %s,"%(spaces_pattern, address_pattern),
                r"%scharOut = %s,"%(spaces_pattern, address_pattern),
                r"%sautoFlush = 0x1,"%(spaces_pattern),
                r"%sclosing = 0x0"%(spaces_pattern),
                r"}"]

        checker = Checker("print DefaultGreeterSystem.out", rexp)

        checker.check(exec_string, skip_fails=True)

        # print the hub name field and check it is java.io.PrintStream
        # n.b. the expected String text is not necessarily null terminated
        # so we need a wild card before the final quote
        exec_string = execute("x/s (('java.io.PrintStream' *)$rdi)->hub->name->value->data")
        checker = Checker("print PrintStream hub name",
                          r"%s:%s\"java.io.PrintStream.*\""%(address_pattern, spaces_pattern))
        checker.check(exec_string, skip_fails=False)

    print(execute("quit 0"))

test()