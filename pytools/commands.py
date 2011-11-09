#
# commands.py - the default commands available to gl.py
#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2007, 2011, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
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

import os
from os.path import join, exists
from collections import Callable

def clean(env, args):
    """cleans the GraalVM source tree"""
    os.environ.update(ARCH_DATA_MODEL='64', LANG='C', HOTSPOT_BUILD_JOBS='16')
    env.run(['gmake', 'clean'], cwd=join(env.graal_home, 'make'))

def bootstrap(env, args):
    return env.run_vm(args + ['-version'])

def avrora(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '20', 'avrora'])

def batik(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '20', 'batik'])

def eclipse(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '20', 'eclipse'])

def fop(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '100', 'fop'])

def h2(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '10', 'h2'])

def jython(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '10', 'jython'])

def lusearch(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '5', 'lusearch'])

def pmd(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '10', 'pmd'])

def tradebeans(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '20', 'tradebeans'])

def xalan(env, args):
    return env.run_dacapo(args + ['Harness', '--preserve', '-n', '20', 'xalan'])

def tests(env, args):
    """run a selection of the Maxine JTT tests in Graal"""
    
    def jtt(name):
        return join(env.maxine, 'com.oracle.max.vm', 'test', 'jtt', name)
    
    return env.run_vm(args + ['-ea', '-esa', '-Xcomp', '-XX:+PrintCompilation', '-XX:CompileOnly=jtt'] + args +
                       ['-Xbootclasspath/p:' + join(env.maxine, 'com.oracle.max.vm', 'bin'), 
                        '-Xbootclasspath/p:' + join(env.maxine, 'com.oracle.max.base', 'bin'),
                        'test.com.sun.max.vm.compiler.JavaTester',
                        '-verbose=1', '-gen-run-scheme=false', '-run-scheme-package=all',
                        jtt('bytecode'),
                        jtt('except'), 
                        jtt('jdk'), 
                        jtt('hotpath'), 
                        jtt('jdk'), 
                        jtt('lang'), 
                        jtt('loop'), 
                        jtt('micro'), 
                        jtt('optimize'), 
                        jtt('reflect'), 
                        jtt('threads'), 
                        jtt('hotspot')])

def help_(env, args):
    """show help for a given command

With no arguments, print a list of commands and short help for each command.

Given a command name, print help for that command."""
    if len(args) == 0:
        env.print_help()
        return
    
    name = args[0]
    if not table.has_key(name):
        env.error('unknown command: ' + name)
    
    value = table[name]
    (func, usage) = value[:2]
    doc = func.__doc__
    if len(value) > 2:
        docArgs = value[2:]
        fmtArgs = []
        for d in docArgs:
            if isinstance(d, Callable):
                fmtArgs += [d(env)]
            else:
                fmtArgs += [str(d)]
        doc = doc.format(*fmtArgs)
    print 'gl {0} {1}\n\n{2}\n'.format(name, usage, doc)

def make(env, args):
    """builds the GraalVM binary"""

    def fix_jvm_cfg(env, jdk):
        jvmCfg = join(jdk, 'jre', 'lib', 'amd64', 'jvm.cfg')
        found = False
        if not exists(jvmCfg):
            env.abort(jvmCfg + ' does not exist')
            
        with open(jvmCfg) as f:
            for line in f:
                if '-graal KNOWN' in line:
                    found = True
                    break
        if not found:
            env.log('Appending "-graal KNOWN" to ' + jvmCfg)
            with open(jvmCfg, 'a') as f:
                f.write('-graal KNOWN\n')

    fix_jvm_cfg(env, env.jdk7)

    if env.get_os() != 'windows':
        javaLink = join(env.graal_home, 'hotspot', 'java')
        if not exists(javaLink):
            javaExe = join(env.jdk7, 'jre', 'bin', 'java')
            env.log('Creating link: ' + javaLink + ' -> ' + javaExe)
            os.symlink(javaExe, javaLink)

    graalVmDir = join(env.jdk7, 'jre', 'lib', 'amd64', 'graal')
    if not exists(graalVmDir):
        env.log('Creating Graal directory in JDK7: ' + graalVmDir)
        os.makedirs(graalVmDir)

    os.environ.update(ARCH_DATA_MODEL='64', LANG='C', HOTSPOT_BUILD_JOBS='4', ALT_BOOTDIR=env.jdk7, INSTALL='y')
    env.run(['gmake', 'jvmggraal'], cwd=join(env.graal_home, 'make'))

    os.environ.update(ARCH_DATA_MODEL='64', LANG='C', HOTSPOT_BUILD_JOBS='4', ALT_BOOTDIR=env.jdk7, INSTALL='y')
    env.run(['gmake', 'productgraal'], cwd=join(env.graal_home, 'make'))
    
def vm(env, args):
    return env.run_vm(args)

# Table of commands in alphabetical order.
# Keys are command names, value are lists: [<function>, <usage msg>, <format args to doc string of function>...]
# If any of the format args are instances of Callable, then they are called with an 'env' are before being
# used in the call to str.format().  
# Extensions should update this table directly
table = {
    'avrora': [avrora, ''],
    'batik': [batik, ''],
    'bootstrap': [bootstrap, ''],
    'clean': [clean, ''],
    'fop': [fop, ''],
    'h2': [h2, ''],
    'jython': [jython, ''],
    'lusearch': [lusearch, ''],
    'pmd': [pmd, ''],
    'tradebeans': [tradebeans, ''],
    'tests': [tests, ''],
    'help': [help_, '[command]'],
    'make': [make, ''],
    'xalan': [xalan, ''],
    'vm': [vm, ''],
}
