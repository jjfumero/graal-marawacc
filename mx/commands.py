#
# commands.py - the GraalVM specific commands
#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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

import os, sys, shutil, zipfile, tempfile, re, time, datetime, platform, subprocess, multiprocessing
from os.path import join, exists, dirname, basename
from argparse import ArgumentParser, REMAINDER
import mx
import sanitycheck
import json

_graal_home = dirname(dirname(__file__))

""" Used to distinguish an exported GraalVM (see 'mx export'). """
_vmSourcesAvailable = exists(join(_graal_home, 'make')) and exists(join(_graal_home, 'src'))

""" The VM that will be run by the 'vm' command: graal(default), client or server.
    This can be set via the global '--vm' option. """
_vm = 'graal'

""" The VM build that will be run by the 'vm' command: product(default), fastdebug or debug.
    This can be set via the global '--fastdebug' and '--debug' options. """
_vmbuild = 'product'

_jacoco = 'off'

_native_dbg = None

_make_eclipse_launch = False

_copyrightTemplate = """/*
 * Copyright (c) {0}, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

"""

_minVersion = mx.JavaVersion('1.7.0_04')

def _chmodDir(chmodFlags, dirname, fnames):
    os.chmod(dirname, chmodFlags)
    for name in fnames:
        os.chmod(os.path.join(dirname, name), chmodFlags)

def chmodRecursive(dirname, chmodFlags):
    os.path.walk(dirname, _chmodDir, chmodFlags)

def clean(args):
    """clean the GraalVM source tree"""
    opts = mx.clean(args, parser=ArgumentParser(prog='mx clean'))
    if opts.native:
        os.environ.update(ARCH_DATA_MODEL='64', LANG='C', HOTSPOT_BUILD_JOBS='16')
        mx.run([mx.gmake_cmd(), 'clean'], cwd=join(_graal_home, 'make'))
        jdks = join(_graal_home, 'jdk' + str(mx.java().version))
        if exists(jdks):
            shutil.rmtree(jdks)

def export(args):
    """create a GraalVM zip file for distribution"""

    parser = ArgumentParser(prog='mx export');
    parser.add_argument('--omit-vm-build', action='store_false', dest='vmbuild', help='omit VM build step')
    parser.add_argument('--omit-dist-init', action='store_false', dest='distInit', help='omit class files and IDE configurations from distribution')
    parser.add_argument('zipfile', nargs=REMAINDER, metavar='zipfile')

    args = parser.parse_args(args)

    tmp = tempfile.mkdtemp(prefix='tmp', dir=_graal_home)
    if args.vmbuild:
        # Make sure the product VM binary is up to date
        build(['product'])

    mx.log('Copying Java sources and mx files...')
    mx.run(('hg archive -I graal -I mx -I mxtool -I mx.sh ' + tmp).split())

    # Copy the GraalVM JDK
    mx.log('Copying GraalVM JDK...')
    src = _jdk()
    dst = join(tmp, basename(src))
    shutil.copytree(src, dst)
    zfName = join(_graal_home, 'graalvm-' + mx.get_os() + '.zip')
    zf = zipfile.ZipFile(zfName, 'w')
    for root, _, files in os.walk(tmp):
        for f in files:
            name = join(root, f)
            arcname = name[len(tmp) + 1:]
            zf.write(join(tmp, name), arcname)

    # create class files and IDE configurations
    if args.distInit:
        mx.log('Creating class files...')
        mx.run('mx build'.split(), cwd=tmp)
        mx.log('Creating IDE configurations...')
        mx.run('mx ideinit'.split(), cwd=tmp)

    # clean up temp directory
    mx.log('Cleaning up...')
    shutil.rmtree(tmp)

    mx.log('Created distribution in ' + zfName)

def example(args):
    """run some or all Graal examples"""
    examples = {
        'safeadd': ['com.oracle.graal.examples.safeadd', 'com.oracle.graal.examples.safeadd.Main'],
        'vectorlib': ['com.oracle.graal.examples.vectorlib', 'com.oracle.graal.examples.vectorlib.Main'],
    }

    def run_example(verbose, project, mainClass):
        cp = mx.classpath(project)
        sharedArgs = ['-Xcomp', '-XX:CompileOnly=Main', mainClass]

        res = []
        mx.log("=== Server VM ===")
        printArg = '-XX:+PrintCompilation' if verbose else '-XX:-PrintCompilation'
        res.append(vm(['-cp', cp, printArg] + sharedArgs, vm='server'))
        mx.log("=== Graal VM ===")
        printArg = '-G:+PrintCompilation' if verbose else '-G:-PrintCompilation'
        res.append(vm(['-cp', cp, printArg, '-G:-Extend', '-G:-Inline'] + sharedArgs))
        mx.log("=== Graal VM with extensions ===")
        res.append(vm(['-cp', cp, printArg, '-G:+Extend', '-G:-Inline'] + sharedArgs))

        if len([x for x in res if x != 0]) != 0:
            return 1
        return 0

    verbose = False
    if '-v' in args:
        verbose = True
        args = [a for a in args if a != '-v']

    if len(args) == 0:
        args = examples.keys()
    for a in args:
        config = examples.get(a)
        if config is None:
            mx.log('unknown example: ' + a + '  {available examples = ' + str(examples.keys()) + '}')
        else:
            mx.log('--------- ' + a + ' ------------')
            project, mainClass = config
            run_example(verbose, project, mainClass)

def dacapo(args):
    """run one or all DaCapo benchmarks

    DaCapo options are distinguished from VM options by a '@' prefix.
    For example, '@-n @5' will pass '-n 5' to the
    DaCapo harness."""

    numTests = {}
    if len(args) > 0:
        level = getattr(sanitycheck.SanityCheckLevel, args[0], None)
        if level is not None:
            del args[0]
            for (bench, ns) in sanitycheck.dacapoSanityWarmup.items():
                if ns[level] > 0:
                    numTests[bench] = ns[level]
        else:
            while len(args) != 0 and args[0][0] not in ['-', '@']:
                n = 1
                if args[0].isdigit():
                    n = int(args[0])
                    assert len(args) > 1 and args[1][0] not in ['-', '@'] and not args[1].isdigit()
                    bm = args[1]
                    del args[0]
                else:
                    bm = args[0]

                del args[0]
                if bm not in sanitycheck.dacapoSanityWarmup.keys():
                    mx.abort('unknown benchmark: ' + bm + '\nselect one of: ' + str(sanitycheck.dacapoSanityWarmup.keys()))
                numTests[bm] = n

    if len(numTests) is 0:
        for bench in sanitycheck.dacapoSanityWarmup.keys():
            numTests[bench] = 1

    # Extract DaCapo options
    dacapoArgs = [(arg[1:]) for arg in args if arg.startswith('@')]

    # The remainder are VM options
    vmOpts = [arg for arg in args if not arg.startswith('@')]
    vm = _vm

    failed = []
    for (test, n) in numTests.items():
        if not sanitycheck.getDacapo(test, n, dacapoArgs).test(vm, opts=vmOpts):
            failed.append(test)

    if len(failed) != 0:
        mx.abort('DaCapo failures: ' + str(failed))

def scaladacapo(args):
    """run one or all Scala DaCapo benchmarks

    Scala DaCapo options are distinguished from VM options by a '@' prefix.
    For example, '@--iterations @5' will pass '--iterations 5' to the
    DaCapo harness."""

    numTests = {}

    if len(args) > 0:
        level = getattr(sanitycheck.SanityCheckLevel, args[0], None)
        if level is not None:
            del args[0]
            for (bench, ns) in sanitycheck.dacapoScalaSanityWarmup.items():
                if ns[level] > 0:
                    numTests[bench] = ns[level]
        else:
            while len(args) != 0 and args[0][0] not in ['-', '@']:
                n = 1
                if args[0].isdigit():
                    n = int(args[0])
                    assert len(args) > 1 and args[1][0] not in ['-', '@'] and not args[1].isdigit()
                    bm = args[1]
                    del args[0]
                else:
                    bm = args[0]

                del args[0]
                if bm not in sanitycheck.dacapoScalaSanityWarmup.keys():
                    mx.abort('unknown benchmark: ' + bm + '\nselect one of: ' + str(sanitycheck.dacapoScalaSanityWarmup.keys()))
                numTests[bm] = n

    if len(numTests) is 0:
        for bench in sanitycheck.dacapoScalaSanityWarmup.keys():
            numTests[bench] = 1

    # Extract DaCapo options
    dacapoArgs = [(arg[1:]) for arg in args if arg.startswith('@')]

    # The remainder are VM options
    vmOpts = [arg for arg in args if not arg.startswith('@')]
    vm = _vm;

    failed = []
    for (test, n) in numTests.items():
        if not sanitycheck.getScalaDacapo(test, n, dacapoArgs).test(vm, opts=vmOpts):
            failed.append(test)

    if len(failed) != 0:
        mx.abort('Scala DaCapo failures: ' + str(failed))

def _arch():
    machine = platform.uname()[4]
    if machine in ['amd64', 'AMD64', 'x86_64', 'i86pc']:
        return 'amd64'
    if machine == 'i386' and mx.get_os() == 'darwin':
        try:
            # Support for Snow Leopard and earlier version of MacOSX
            if subprocess.check_output(['sysctl', '-n', 'hw.cpu64bit_capable']).strip() == '1':
                return 'amd64'
        except OSError:
            # sysctl is not available
            pass
    mx.abort('unknown or unsupported architecture: os=' + mx.get_os() + ', machine=' + machine)

def _vmLibDirInJdk(jdk):
    """
    Get the directory within a JDK where the server and client
    subdirectories are located.
    """
    if platform.system() == 'Darwin':
        return join(jdk, 'jre', 'lib')
    if platform.system() == 'Windows':
        return join(jdk, 'jre', 'bin')
    return join(jdk, 'jre', 'lib', _arch())

def _vmCfgInJdk(jdk):
    """
    Get the jvm.cfg file.
    """
    if platform.system() == 'Windows':
        return join(jdk, 'jre', 'lib', _arch(), 'jvm.cfg')
    return join(_vmLibDirInJdk(jdk), 'jvm.cfg')

def _jdk(build='product', create=False):
    """
    Get the JDK into which Graal is installed, creating it first if necessary.
    """
    jdk = join(_graal_home, 'jdk' + str(mx.java().version), build)
    jdkContents = ['bin', 'include', 'jre', 'lib']
    if (exists(join(jdk, 'db'))):
        jdkContents.append('db')
    if mx.get_os() != 'windows':
        jdkContents.append('man')
    if create:
        if not exists(jdk):
            srcJdk = mx.java().jdk
            mx.log('Creating ' + jdk + ' from ' + srcJdk)
            os.makedirs(jdk)
            for d in jdkContents:
                src = join(srcJdk, d)
                dst = join(jdk, d)
                if not exists(src):
                    mx.abort('Host JDK directory is missing: ' + src)
                shutil.copytree(src, dst)

            # Make a copy of the default VM so that this JDK can be
            # reliably used as the bootstrap for a HotSpot build.
            jvmCfg = _vmCfgInJdk(jdk)
            if not exists(jvmCfg):
                mx.abort(jvmCfg + ' does not exist')

            lines = []
            defaultVM = None
            with open(jvmCfg) as f:
                for line in f:
                    if line.startswith('-') and defaultVM is None:
                        parts = line.split()
                        assert len(parts) == 2, parts
                        assert parts[1] == 'KNOWN', parts[1]
                        defaultVM = parts[0][1:]
                        lines.append('-' + defaultVM + '0 KNOWN\n')
                    lines.append(line)

            assert defaultVM is not None, 'Could not find default VM in ' + jvmCfg
            if mx.get_os() != 'windows':
                chmodRecursive(jdk, 0755)
            shutil.copytree(join(_vmLibDirInJdk(jdk), defaultVM), join(_vmLibDirInJdk(jdk), defaultVM + '0'))

            with open(jvmCfg, 'w') as f:
                for line in lines:
                    f.write(line)

            # Install a copy of the disassembler library
            try:
                hsdis([], copyToDir=_vmLibDirInJdk(jdk))
            except SystemExit:
                pass
    else:
        if not exists(jdk):
            mx.abort('The ' + build + ' VM has not been created - run \'mx clean; mx build ' + build + '\'')
            
    _installGraalJarInJdks(mx.distribution('GRAAL'))
    
    return jdk

def _installGraalJarInJdks(graalDist):
    graalJar = graalDist.path
    jdks = join(_graal_home, 'jdk' + str(mx.java().version))
    if exists(jdks):
        for e in os.listdir(jdks):
            jreLibDir = join(jdks, e, 'jre', 'lib')
            if exists(jreLibDir):
                # do a copy and then a move to get atomic updating (on Unix) of graal.jar in the JRE
                _, tmp = tempfile.mkstemp(suffix='', prefix='graal.jar', dir=jreLibDir)
                shutil.copyfile(graalJar, tmp)
                shutil.move(tmp, join(jreLibDir, 'graal.jar'))

# run a command in the windows SDK Debug Shell
def _runInDebugShell(cmd, workingDir, logFile=None, findInOutput=None, respondTo={}):
    newLine = os.linesep
    STARTTOKEN = 'RUNINDEBUGSHELL_STARTSEQUENCE'
    ENDTOKEN = 'RUNINDEBUGSHELL_ENDSEQUENCE'

    winSDK = mx.get_env('WIN_SDK', 'C:\\Program Files\\Microsoft SDKs\\Windows\\v7.1\\')
    
    if not exists(winSDK):
        mx.abort("Could not find Windows SDK : '" + winSDK + "' does not exist")
        
    if not exists(join(winSDK, 'Bin', 'SetEnv.cmd')):
        mx.abort("Invalid Windows SDK path (" + winSDK + ") : could not find Bin/SetEnv.cmd (you can use the WIN_SDK environment variable to specify an other path)")

    p = subprocess.Popen('cmd.exe /E:ON /V:ON /K ""' + winSDK + '/Bin/SetEnv.cmd" & echo ' + STARTTOKEN + '"', \
            shell=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    stdout = p.stdout
    stdin = p.stdin
    if logFile:
        log = open(logFile, 'w')
    ret = False
    while True:
        
        # encoding may be None on windows plattforms
        if sys.stdout.encoding is None:
            encoding = 'utf-8'
        else:
            encoding = sys.stdout.encoding
        
        line = stdout.readline().decode(encoding)
        if logFile:
            log.write(line.encode('utf-8'))
        line = line.strip()
        mx.log(line)
        if line == STARTTOKEN:
            stdin.write('cd /D ' + workingDir + ' & ' + cmd + ' & echo ' + ENDTOKEN + newLine)
        for regex in respondTo.keys():
            match = regex.search(line)
            if match:
                stdin.write(respondTo[regex] + newLine)
        if findInOutput:
            match = findInOutput.search(line)
            if match:
                ret = True
        if line == ENDTOKEN:
            if not findInOutput:
                stdin.write('echo ERRXXX%errorlevel%' + newLine)
            else:
                break
        if line.startswith('ERRXXX'):
            if line == 'ERRXXX0':
                ret = True
            break;
    stdin.write('exit' + newLine)
    if logFile:
        log.close()
    return ret

def jdkhome(args, vm=None):
    """print the JDK directory selected for the 'vm' command"""

    build = _vmbuild if _vmSourcesAvailable else 'product'
    print join(_graal_home, 'jdk' + str(mx.java().version), build)

def build(args, vm=None):
    """build the VM binary

    The global '--vm' option selects which VM to build. This command also
    compiles the Graal classes irrespective of what VM is being built.
    The optional last argument specifies what build level is to be used
    for the VM binary."""

    # Call mx.build to compile the Java sources
    opts2 = mx.build(['--source', '1.7'] + args, parser=ArgumentParser(prog='mx build'))

    if not _vmSourcesAvailable or not opts2.native:
        return

    builds = opts2.remainder
    if len(builds) == 0:
        builds = ['product']

    if vm is None:
        vm = _vm

    if vm == 'server':
        buildSuffix = ''
    elif vm == 'client':
        buildSuffix = '1'
    else:
        assert vm == 'graal', vm
        buildSuffix = 'graal'

    for build in builds:
        if build == 'ide-build-target':
            build = os.environ.get('IDE_BUILD_TARGET', 'product')
            if len(build) == 0:
                mx.log('[skipping build from IDE as IDE_BUILD_TARGET environment variable is ""]')
                continue

        jdk = _jdk(build, create=True)

        vmDir = join(_vmLibDirInJdk(jdk), vm)
        if not exists(vmDir):
            if mx.get_os() != 'windows':
                chmodRecursive(jdk, 0755)
            mx.log('Creating VM directory in JDK7: ' + vmDir)
            os.makedirs(vmDir)

        def filterXusage(line):
            if not 'Xusage.txt' in line:
                sys.stderr.write(line + os.linesep)

        # Check if a build really needs to be done
        timestampFile = join(vmDir, '.build-timestamp')
        if opts2.force or not exists(timestampFile):
            mustBuild = True
        else:
            mustBuild = False
            timestamp = os.path.getmtime(timestampFile)
            sources = []
            for d in ['src', 'make']:
                for root, dirnames, files in os.walk(join(_graal_home, d)):
                    # ignore <graal>/src/share/tools
                    if root == join(_graal_home, 'src', 'share'):
                        dirnames.remove('tools')
                    sources += [join(root, name) for name in files]
            for f in sources:
                if len(f) != 0 and os.path.getmtime(f) > timestamp:
                    mustBuild = True
                    break

        if not mustBuild:
            mx.log('[all files in src and make directories are older than ' + timestampFile[len(_graal_home) + 1:] + ' - skipping native build]')
            continue

        if platform.system() == 'Windows':
            compilelogfile = _graal_home + '/graalCompile.log'
            mksHome = mx.get_env('MKS_HOME', 'C:\\cygwin\\bin')

            variant = {'client': 'compiler1', 'server': 'compiler2'}.get(vm, vm)
            project_config = variant + '_' + build
            _runInDebugShell('msbuild ' + _graal_home + r'\build\vs-amd64\jvm.vcproj /p:Configuration=' + project_config + ' /target:clean', _graal_home)
            winCompileCmd = r'set HotSpotMksHome=' + mksHome + r'& set OUT_DIR=' + jdk + r'& set JAVA_HOME=' + jdk + r'& set path=%JAVA_HOME%\bin;%path%;%HotSpotMksHome%& cd /D "' +_graal_home + r'\make\windows"& call create.bat ' + _graal_home
            print(winCompileCmd)
            winCompileSuccess = re.compile(r"^Writing \.vcxproj file:")
            if not _runInDebugShell(winCompileCmd, _graal_home, compilelogfile, winCompileSuccess):
                mx.log('Error executing create command')
                return
            winBuildCmd = 'msbuild ' + _graal_home + r'\build\vs-amd64\jvm.vcxproj /p:Configuration=' + project_config + ' /p:Platform=x64'
            if not _runInDebugShell(winBuildCmd, _graal_home, compilelogfile):
                mx.log('Error building project')
                return
        else:
            cpus = multiprocessing.cpu_count()
            runCmd = [mx.gmake_cmd()]
            if build == 'debug':
                build = 'jvmg'
            runCmd.append(build + buildSuffix) 
            env = os.environ
            env.setdefault('ARCH_DATA_MODEL', '64')
            env.setdefault('LANG', 'C')
            env.setdefault('HOTSPOT_BUILD_JOBS', str(cpus))
            env['ALT_BOOTDIR'] = jdk
            if not env.has_key('OMIT_GRAAL'):
                env['GRAAL'] = join(_graal_home, 'graal') # needed for TEST_IN_BUILD
            env.setdefault('INSTALL', 'y')
            if mx.get_os() == 'solaris' :
                # If using sparcWorks, setup flags to avoid make complaining about CC version
                cCompilerVersion = subprocess.Popen('CC -V', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True).stderr.readlines()[0]
                if cCompilerVersion.startswith('CC: Sun C++') :
                    compilerRev = cCompilerVersion.split(' ')[3]
                    env.setdefault('ENFORCE_COMPILER_REV', compilerRev)
                    env.setdefault('ENFORCE_CC_COMPILER_REV', compilerRev)
                    if build == 'jvmg':
                        # I want ALL the symbols when I'm debugging on Solaris
                        # Some Makefile variable are overloaded by environment variable so we need to explicitely
                        # pass them down in the command line. This one is an example of that.
                        runCmd.append('STRIP_POLICY=no_strip')
            # This removes the need to unzip the *.diz files before debugging in gdb
            env.setdefault('ZIP_DEBUGINFO_FILES', '0')

            # We don't need to run the Queens test (i.e. test_gamma)
            env.setdefault('TEST_IN_BUILD', 'false')

            # Clear these 2 variables as having them set can cause very confusing build problems
            env.pop('LD_LIBRARY_PATH', None)
            env.pop('CLASSPATH', None)

            mx.run(runCmd, cwd=join(_graal_home, 'make'), err=filterXusage)

        jvmCfg = _vmCfgInJdk(jdk)
        found = False
        if not exists(jvmCfg):
            mx.abort(jvmCfg + ' does not exist')

        prefix = '-' + vm
        vmKnown = prefix + ' KNOWN\n'
        lines = []
        with open(jvmCfg) as f:
            for line in f:
                if vmKnown in line:
                    found = True
                    break
                if not line.startswith(prefix):
                    lines.append(line)
        if not found:
            mx.log('Appending "' + prefix + ' KNOWN" to ' + jvmCfg)
            lines.append(vmKnown)
            if mx.get_os() != 'windows':
                os.chmod(jvmCfg, 0755)
            with open(jvmCfg, 'w') as f:
                for line in lines:
                    f.write(line)

        if exists(timestampFile):
            os.utime(timestampFile, None)
        else:
            file(timestampFile, 'a')

def vmg(args):
    """run the debug build of VM selected by the '--vm' option"""
    return vm(args, vmbuild='debug')

def vmfg(args):
    """run the fastdebug build of VM selected by the '--vm' option"""
    return vm(args, vmbuild='fastdebug')

def vm(args, vm=None, nonZeroIsFatal=True, out=None, err=None, cwd=None, timeout=None, vmbuild=None):
    """run the VM selected by the '--vm' option"""

    if vm is None:
        vm = _vm

    build = vmbuild if vmbuild is not None else _vmbuild if _vmSourcesAvailable else 'product'
    jdk = _jdk(build)
    mx.expand_project_in_args(args)
    if _make_eclipse_launch:
        mx.make_eclipse_launch(args, 'graal-' + build, name=None, deps=mx.project('com.oracle.graal.hotspot').all_deps([], True))
    if len([a for a in args if 'PrintAssembly' in a]) != 0:
        hsdis([], copyToDir=_vmLibDirInJdk(jdk))
    if mx.java().debug_port is not None:
        args = ['-Xdebug', '-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=' + str(mx.java().debug_port)] + args
    if _jacoco == 'on' or _jacoco == 'append':
        jacocoagent = mx.library("JACOCOAGENT", True)
        # Exclude all compiler tests and snippets
        excludes = ['com.oracle.graal.compiler.tests.*', 'com.oracle.graal.jtt.*']
        for p in mx.projects():
            excludes += _find_classes_with_annotations(p, None, ['@Snippet', '@ClassSubstitution', '@Test'], includeInnerClasses=True)
            excludes += p.find_classes_with_matching_source_line(None, lambda line: 'JaCoCo Exclude' in line, includeInnerClasses=True)
            
        includes = ['com.oracle.graal.*', 'com.oracle.max.*']
        agentOptions = {
                        'append' : 'true' if _jacoco == 'append' else 'false',
                        'bootclasspath' : 'true',
                        'includes' : ':'.join(includes),
                        'excludes' : ':'.join(excludes),
                        'destfile' : 'jacoco.exec'
        }
        args = ['-javaagent:' + jacocoagent.get_path(True) + '=' + ','.join([k + '=' + v for k, v in agentOptions.items()])] + args
    if '-d64' not in args:
        args = ['-d64'] + args

    exe = join(jdk, 'bin', mx.exe_suffix('java'))
    dbg = _native_dbg.split() if _native_dbg is not None else []
    return mx.run(dbg + [exe, '-' + vm] + args, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd, timeout=timeout)

def _find_classes_with_annotations(p, pkgRoot, annotations, includeInnerClasses=False):
    """
    Scan the sources of project 'p' for Java source files containing a line starting with 'annotation'
    (ignoring preceding whitespace) and return the fully qualified class name for each Java
    source file matched in a list.
    """
    
    matches = lambda line : len([a for a in annotations if line == a or line.startswith(a + '(')]) != 0
    return p.find_classes_with_matching_source_line(pkgRoot, matches, includeInnerClasses)

def _run_tests(args, harness):
    pos = [a for a in args if a[0] != '-' and a[0] != '@' ]
    neg = [a[1:] for a in args if a[0] == '-']
    vmArgs = [a[1:] for a in args if a[0] == '@']

    def containsAny(c, substrings):
        for s in substrings:
            if s in c:
                return True
        return False

    for p in mx.projects():
        classes = _find_classes_with_annotations(p, None, ['@Test'])

        if len(pos) != 0:
            classes = [c for c in classes if containsAny(c, pos)]
        if len(neg) != 0:
            classes = [c for c in classes if not containsAny(c, neg)]

        if len(classes) != 0:
            mx.log('running tests in ' + p.name)
            harness(p, vmArgs, classes)

def unittest(args):
    """run the JUnit tests

    If filters are supplied, only tests whose fully qualified name
    include a filter as a substring are run. Negative filters are
    those with a '-' prefix. VM args should have a @ prefix."""

    def harness(p, vmArgs, classes):
        prefixArgs = ['-XX:-BootstrapGraal', '-esa', '-ea']
        vm(prefixArgs + vmArgs + ['-cp', mx.classpath(p.name), 'org.junit.runner.JUnitCore'] + classes)
    _run_tests(args, harness)

def buildvms(args):
    """build one or more VMs in various configurations"""

    parser = ArgumentParser(prog='mx buildvms');
    parser.add_argument('--vms', help='a comma separated list of VMs to build (default: server,client,graal)', default='server,client,graal')
    parser.add_argument('--builds', help='a comma separated list of build types (default: product,fastdebug,debug)', default='product,fastdebug,debug')

    args = parser.parse_args(args)
    vms = args.vms.split(',')
    builds = args.builds.split(',')

    allStart = time.time()
    for v in vms:
        for vmbuild in builds:
            logFile = join(v + '-' + vmbuild + '.log')
            log = open(join(_graal_home, logFile), 'wb')
            start = time.time()
            mx.log('BEGIN: ' + v + '-' + vmbuild + '\t(see: ' + logFile + ')')
            # Run as subprocess so that output can be directed to a file
            subprocess.check_call([sys.executable, '-u', join('mxtool', 'mx.py'), '--vm', v, 'build', vmbuild], cwd=_graal_home, stdout=log, stderr=subprocess.STDOUT)
            duration = datetime.timedelta(seconds=time.time() - start)
            mx.log('END:   ' + v + '-' + vmbuild + '\t[' + str(duration) + ']')
    allDuration = datetime.timedelta(seconds=time.time() - allStart)
    mx.log('TOTAL TIME:   ' + '[' + str(allDuration) + ']')

def gate(args):
    """run the tests used to validate a push

    If this command exits with a 0 exit code, then the source code is in
    a state that would be accepted for integration into the main repository."""

    class Task:
        def __init__(self, title):
            self.start = time.time()
            self.title = title
            self.end = None
            self.duration = None
            mx.log(time.strftime('gate: %d %b %Y %H:%M:%S: BEGIN: ') + title)
        def stop(self):
            self.end = time.time()
            self.duration = datetime.timedelta(seconds=self.end - self.start)
            mx.log(time.strftime('gate: %d %b %Y %H:%M:%S: END:   ') + self.title + ' [' + str(self.duration) + ']')
            return self
        def abort(self, codeOrMessage):
            self.end = time.time()
            self.duration = datetime.timedelta(seconds=self.end - self.start)
            mx.log(time.strftime('gate: %d %b %Y %H:%M:%S: ABORT: ') + self.title + ' [' + str(self.duration) + ']')
            mx.abort(codeOrMessage)
            return self

    parser = ArgumentParser(prog='mx gate');
    parser.add_argument('-j', '--omit-java-clean', action='store_false', dest='cleanJava', help='omit cleaning Java native code')
    parser.add_argument('-n', '--omit-native-clean', action='store_false', dest='cleanNative', help='omit cleaning and building native code')
    parser.add_argument('-g', '--only-build-graalvm', action='store_false', dest='buildNonGraal', help='only build the Graal VM')
    parser.add_argument('--jacocout', help='specify the output directory for jacoco report')

    args = parser.parse_args(args)

    global _vmbuild
    global _vm
    global _jacoco
    
    tasks = []
    total = Task('Gate')
    try:

        t = Task('Clean')
        cleanArgs = []
        if not args.cleanNative:
            cleanArgs.append('--no-native')
        if not args.cleanJava:
            cleanArgs.append('--no-java')
        clean(cleanArgs)
        tasks.append(t.stop())

        eclipse_exe = os.environ.get('ECLIPSE_EXE')
        if eclipse_exe is not None:
            t = Task('CodeFormatCheck')
            if mx.eclipseformat(['-e', eclipse_exe]) != 0:
                t.abort('Formatter modified files - run "mx eclipseformat", check in changes and repush')
            tasks.append(t.stop())
        
        t = Task('BuildJava')
        build(['--no-native', '--jdt-warning-as-error'])
        tasks.append(t.stop())
        
        if exists('jacoco.exec'):
            os.unlink('jacoco.exec')
        
        if args.jacocout is not None:
            _jacoco = 'append'
        else:
            _jacoco = 'off'
        

        t = Task('BuildHotSpotGraal: fastdebug,product')
        buildvms(['--vms', 'graal', '--builds', 'fastdebug,product'])
        tasks.append(t.stop())

        _vmbuild = 'fastdebug'
        t = Task('BootstrapWithSystemAssertions:fastdebug')
        vm(['-esa', '-version'])
        tasks.append(t.stop())

        _vmbuild = 'product'
        t = Task('UnitTests:product')
        unittest([])
        tasks.append(t.stop())

        for vmbuild in ['fastdebug', 'product']:
            for test in sanitycheck.getDacapos(level=sanitycheck.SanityCheckLevel.Gate, gateBuildLevel=vmbuild):
                t = Task(str(test) + ':' + vmbuild)
                if not test.test('graal'):
                    t.abort(test.name + ' Failed')
                tasks.append(t.stop())

        if args.jacocout is not None:
            jacocoreport([args.jacocout])
            
        _jacoco = 'off'

        t = Task('Checkstyle')
        if mx.checkstyle([]) != 0:
            t.abort('Checkstyle warnings were found')
        tasks.append(t.stop())

        t = Task('Canonicalization Check')
        mx.log(time.strftime('%d %b %Y %H:%M:%S - Ensuring mx/projects files are canonicalized...'))
        if mx.canonicalizeprojects([]) != 0:
            t.abort('Rerun "mx canonicalizeprojects" and check-in the modified mx/projects files.')
        tasks.append(t.stop())

        t = Task('CleanAndBuildGraalVisualizer')
        mx.run(['ant', '-f', join(_graal_home, 'visualizer', 'build.xml'), '-q', 'clean', 'build'])
        tasks.append(t.stop())

        # Prevent Graal modifications from breaking the standard builds
        if args.buildNonGraal:
            t = Task('BuildHotSpotVarieties')
            buildvms(['--vms', 'client,server', '--builds', 'fastdebug,product'])
            tasks.append(t.stop())

            for vmbuild in ['product', 'fastdebug']:
                _vmbuild = vmbuild
                for theVm in ['client', 'server']:
                    _vm = theVm

                    t = Task('DaCapo_pmd:' + theVm + ':' + vmbuild)
                    dacapo(['pmd'])
                    tasks.append(t.stop())

                    t = Task('UnitTests:' + theVm + ':' + vmbuild)
                    unittest(['@-XX:CompileCommand=exclude,*::run*', 'graal.api'])
                    tasks.append(t.stop())

    except KeyboardInterrupt:
        total.abort(1)

    except BaseException as e:
        import traceback
        traceback.print_exc()
        total.abort(str(e))

    total.stop()

    mx.log('Gate task times:')
    for t in tasks:
        mx.log('  ' + str(t.duration) + '\t' + t.title)
    mx.log('  =======')
    mx.log('  ' + str(total.duration))
    
def deoptalot(args):
    """Bootstrap a fastdebug Graal VM with DeoptimizeALot and VerifyOops on

    If the first argument is a number, the process will be repeated
    this number of times. All other arguments are passed to the VM."""
    count = 1
    if len(args) > 0 and args[0].isdigit():
        count = int(args[0])
        del args[0]
    
    for _ in range(count):
        if not vm(['-XX:+DeoptimizeALot', '-XX:+VerifyOops'] + args + ['-version'], vmbuild='fastdebug') == 0:
            mx.abort("Failed")
            
def longtests(args):
    
    deoptalot(['15', '-Xmx48m'])
    
    dacapo(['100', 'eclipse', '-esa'])

def gv(args):
    """run the Graal Visualizer"""
    with open(join(_graal_home, '.graal_visualizer.log'), 'w') as fp:
        mx.log('[Graal Visualizer log is in ' + fp.name + ']')
        if not exists(join(_graal_home, 'visualizer', 'build.xml')):
            mx.log('[This initial execution may take a while as the NetBeans platform needs to be downloaded]')
        mx.run(['ant', '-f', join(_graal_home, 'visualizer', 'build.xml'), '-l', fp.name, 'run'])

def igv(args):
    """run the Ideal Graph Visualizer"""
    with open(join(_graal_home, '.ideal_graph_visualizer.log'), 'w') as fp:
        mx.log('[Ideal Graph Visualizer log is in ' + fp.name + ']')
        if not exists(join(_graal_home, 'src', 'share', 'tools', 'IdealGraphVisualizer', 'nbplatform')):
            mx.log('[This initial execution may take a while as the NetBeans platform needs to be downloaded]')
        mx.run(['ant', '-f', join(_graal_home, 'src', 'share', 'tools', 'IdealGraphVisualizer', 'build.xml'), '-l', fp.name, 'run'])

def bench(args):
    """run benchmarks and parse their output for results

    Results are JSON formated : {group : {benchmark : score}}."""
    resultFile = None
    if '-resultfile' in args:
        index = args.index('-resultfile')
        if index + 1 < len(args):
            resultFile = args[index + 1]
            del args[index]
            del args[index]
        else:
            mx.abort('-resultfile must be followed by a file name')
    vm = _vm
    if len(args) is 0:
        args = ['all']

    def benchmarks_in_group(group):
        prefix = group + ':'
        return [a[len(prefix):] for a in args if a.startswith(prefix)]

    results = {}
    benchmarks = []
    #DaCapo
    if ('dacapo' in args or 'all' in args):
        benchmarks += sanitycheck.getDacapos(level=sanitycheck.SanityCheckLevel.Benchmark)
    else:
        dacapos = benchmarks_in_group('dacapo')
        for dacapo in dacapos:
            if dacapo not in sanitycheck.dacapoSanityWarmup.keys():
                mx.abort('Unknown DaCapo : ' + dacapo)
            benchmarks += [sanitycheck.getDacapo(dacapo, sanitycheck.dacapoSanityWarmup[dacapo][sanitycheck.SanityCheckLevel.Benchmark])]

    if ('scaladacapo' in args or 'all' in args):
        benchmarks += sanitycheck.getScalaDacapos(level=sanitycheck.SanityCheckLevel.Benchmark)
    else:
        scaladacapos = benchmarks_in_group('scaladacapo')
        for scaladacapo in scaladacapos:
            if scaladacapo not in sanitycheck.dacapoScalaSanityWarmup.keys():
                mx.abort('Unknown Scala DaCapo : ' + scaladacapo)
            benchmarks += [sanitycheck.getScalaDacapo(scaladacapo, sanitycheck.dacapoScalaSanityWarmup[scaladacapo][sanitycheck.SanityCheckLevel.Benchmark])]

    #Bootstrap
    if ('bootstrap' in args or 'all' in args):
        benchmarks += sanitycheck.getBootstraps()
    #SPECjvm2008
    if ('specjvm2008' in args or 'all' in args):
        benchmarks += [sanitycheck.getSPECjvm2008([], False, True, 120, 120)]
    else:
        specjvms = benchmarks_in_group('specjvm2008')
        for specjvm in specjvms:
            benchmarks += [sanitycheck.getSPECjvm2008([specjvm], False, True, 120, 120)]
            
    if ('specjbb2005' in args or 'all' in args):
        benchmarks += [sanitycheck.getSPECjbb2005()]
        
    if ('specjbb2013' in args): # or 'all' in args //currently not in default set
        benchmarks += [sanitycheck.getSPECjbb2013()]

    for test in benchmarks:
        for (groupName, res) in test.bench(vm).items():
            group = results.setdefault(groupName, {})
            group.update(res)
    mx.log(json.dumps(results))
    if resultFile:
        with open(resultFile, 'w') as f:
            f.write(json.dumps(results))

def specjvm2008(args):
    """run one or all SPECjvm2008 benchmarks

    All options begining with - will be passed to the vm except for -ikv -ict -wt and -it.
    Other options are supposed to be benchmark names and will be passed to SPECjvm2008."""
    benchArgs = [a for a in args if a[0] != '-']
    vmArgs = [a for a in args if a[0] == '-']
    wt = None
    it = None
    skipValid = False
    skipCheck = False
    if '-v' in vmArgs:
        vmArgs.remove('-v')
        benchArgs.append('-v')
    if '-ict' in vmArgs:
        skipCheck = True
        vmArgs.remove('-ict')
    if '-ikv' in vmArgs:
        skipValid = True
        vmArgs.remove('-ikv')
    if '-wt' in vmArgs:
        wtIdx = args.index('-wt')
        try:
            wt = int(args[wtIdx+1])
        except:
            mx.abort('-wt (Warmup time) needs a numeric value (seconds)')
        vmArgs.remove('-wt')
        benchArgs.remove(args[wtIdx+1])
    if '-it' in vmArgs:
        itIdx = args.index('-it')
        try:
            it = int(args[itIdx+1])
        except:
            mx.abort('-it (Iteration time) needs a numeric value (seconds)')
        vmArgs.remove('-it')
        benchArgs.remove(args[itIdx+1])
    vm = _vm;
    sanitycheck.getSPECjvm2008(benchArgs, skipCheck, skipValid, wt, it).bench(vm, opts=vmArgs)
    
def specjbb2013(args):
    """runs the composite SPECjbb2013 benchmark

    All options begining with - will be passed to the vm"""
    benchArgs = [a for a in args if a[0] != '-']
    vmArgs = [a for a in args if a[0] == '-']
    vm = _vm;
    sanitycheck.getSPECjbb2013(benchArgs).bench(vm, opts=vmArgs)

def hsdis(args, copyToDir=None):
    """download the hsdis library

    This is needed to support HotSpot's assembly dumping features.
    By default it downloads the Intel syntax version, use the 'att' argument to install AT&T syntax."""
    flavor = 'intel'
    if 'att' in args:
        flavor = 'att'
    lib = mx.lib_suffix('hsdis-' + _arch())
    path = join(_graal_home, 'lib', lib)
    if not exists(path):
        mx.download(path, ['http://lafo.ssw.uni-linz.ac.at/hsdis/' + flavor + "/" + lib])
    if copyToDir is not None and exists(copyToDir):
        shutil.copy(path, copyToDir)

def hcfdis(args):
    """disassemble HexCodeFiles embedded in text files

    Run a tool over the input files to convert all embedded HexCodeFiles
    to a disassembled format."""

    parser = ArgumentParser(prog='mx hcfdis');
    parser.add_argument('-m', '--map', help='address to symbol map applied to disassembler output')
    parser.add_argument('files', nargs=REMAINDER, metavar='files...')

    args = parser.parse_args(args)
    
    path = join(_graal_home, 'lib', 'hcfdis-1.jar')
    if not exists(path):
        mx.download(path, ['http://lafo.ssw.uni-linz.ac.at/hcfdis-1.jar'])
    mx.run_java(['-jar', path] + args.files)
    
    if args.map is not None:
        addressRE = re.compile(r'0[xX]([A-Fa-f0-9]+)')
        with open(args.map) as fp:
            lines = fp.read().splitlines()
        symbols = dict()
        for l in lines:
            addressAndSymbol = l.split(' ', 1)
            if len(addressAndSymbol) == 2:
                address, symbol = addressAndSymbol;
                if address.startswith('0x'): 
                    address = long(address, 16)
                    symbols[address] = symbol
        for f in args.files:
            with open(f) as fp:
                lines = fp.read().splitlines()
            updated = False
            for i in range(0, len(lines)):
                l = lines[i]
                for m in addressRE.finditer(l):
                    sval = m.group(0)
                    val = long(sval, 16)
                    sym = symbols.get(val)
                    if sym:
                        l = l.replace(sval, sym)
                        updated = True
                        lines[i] = l
            if updated:
                mx.log('updating ' + f)
                with open('new_' + f, "w") as fp:
                    for l in lines:
                        print >> fp, l

def jacocoreport(args):
    """create a JaCoCo coverage report

    Creates the report from the 'jacoco.exec' file in the current directory.
    Default output directory is 'coverage', but an alternative can be provided as an argument."""
    jacocoreport = mx.library("JACOCOREPORT", True)
    out = 'coverage'
    if len(args) == 1:
        out = args[0]
    elif len(args) > 1:
        mx.abort('jacocoreport takes only one argument : an output directory')
    mx.run_java(['-jar', jacocoreport.get_path(True), '-in', 'jacoco.exec', '-g', join(_graal_home, 'graal'), out])

def site(args):
    """create a website containing javadoc and the project dependency graph"""

    return mx.site(['--name', 'Graal',
                    '--jd', '@-tag', '--jd', '@test:X',
                    '--jd', '@-tag', '--jd', '@run:X',
                    '--jd', '@-tag', '--jd', '@bug:X',
                    '--jd', '@-tag', '--jd', '@summary:X',
                    '--jd', '@-tag', '--jd', '@vmoption:X',
                    '--overview', join(_graal_home, 'graal', 'overview.html'),
                    '--title', 'Graal OpenJDK Project Documentation',
                    '--dot-output-base', 'projects'] + args)

def mx_init():
    _vmbuild = 'product'
    commands = {
        'build': [build, '[-options]'],
        'buildvms': [buildvms, '[-options]'],
        'clean': [clean, ''],
        'hsdis': [hsdis, '[att]'],
        'hcfdis': [hcfdis, ''],
        'igv' : [igv, ''],
        'jdkhome': [jdkhome, ''],
        'dacapo': [dacapo, '[[n] benchmark] [VM options|@DaCapo options]'],
        'scaladacapo': [scaladacapo, '[[n] benchmark] [VM options|@Scala DaCapo options]'],
        'specjvm2008': [specjvm2008, '[VM options|specjvm2008 options (-v, -ikv, -ict, -wt, -it)]'],
        'specjbb2013': [specjbb2013, '[VM options]'],
        #'example': [example, '[-v] example names...'],
        'gate' : [gate, '[-options]'],
        'gv' : [gv, ''],
        'bench' : [bench, '[-resultfile file] [all(default)|dacapo|specjvm2008|bootstrap]'],
        'unittest' : [unittest, '[filters...]'],
        'jacocoreport' : [jacocoreport, '[output directory]'],
        'site' : [site, '[-options]'],
        'vm': [vm, '[-options] class [args...]'],
        'vmg': [vmg, '[-options] class [args...]'],
        'vmfg': [vmfg, '[-options] class [args...]'],
        'deoptalot' : [deoptalot, '[n]'],
        'longtests' : [longtests, '']
    }

    mx.add_argument('--jacoco', help='instruments com.oracle.* classes using JaCoCo', default='off', choices=['off', 'on', 'append'])

    if (_vmSourcesAvailable):
        mx.add_argument('--vm', action='store', dest='vm', default='graal', choices=['graal', 'server', 'client', 'server0'], help='the VM to build/run (default: graal)')
        mx.add_argument('--product', action='store_const', dest='vmbuild', const='product', help='select the product build of the VM')
        mx.add_argument('--debug', action='store_const', dest='vmbuild', const='debug', help='select the debug build of the VM')
        mx.add_argument('--fastdebug', action='store_const', dest='vmbuild', const='fastdebug', help='select the fast debug build of the VM')
        mx.add_argument('--ecl', action='store_true', dest='make_eclipse_launch', help='create launch configuration for running VM execution(s) in Eclipse')
        mx.add_argument('--native-dbg', action='store', dest='native_dbg', help='Start the vm inside a debugger', metavar='<debugger>')
        mx.add_argument('--gdb', action='store_const', const='/usr/bin/gdb --args', dest='native_dbg', help='alias for --native-dbg /usr/bin/gdb -- args')
        

        commands.update({
            'export': [export, '[-options] [zipfile]'],
            'build': [build, '[-options] [product|debug|fastdebug]...']
        })

    mx.commands.update(commands)

def mx_post_parse_cmd_line(opts):#
    # TODO _minVersion check could probably be part of a Suite in mx?
    if (mx.java().version < _minVersion) :
        mx.abort('Requires Java version ' + str(_minVersion) + ' or greater, got version ' + str(mx.java().version))

    if (_vmSourcesAvailable):
        if hasattr(opts, 'vm') and opts.vm is not None:
            global _vm
            _vm = opts.vm
        if hasattr(opts, 'vmbuild') and opts.vmbuild is not None:
            global _vmbuild
            _vmbuild = opts.vmbuild
        global _make_eclipse_launch
        _make_eclipse_launch = getattr(opts, 'make_eclipse_launch', False)
    global _jacoco
    _jacoco = opts.jacoco
    global _native_dbg
    _native_dbg = opts.native_dbg

    mx.distribution('GRAAL').add_update_listener(_installGraalJarInJdks)
