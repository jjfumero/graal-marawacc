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

import os, sys, shutil, StringIO, zipfile, tempfile, re
from os.path import join, exists, dirname, isdir, isfile, isabs, basename
from argparse import ArgumentParser, REMAINDER
import mx

_graal_home = dirname(dirname(__file__))
_vmSourcesAvailable = exists(join(_graal_home, 'make')) and exists(join(_graal_home, 'src')) 
_vmbuild = 'product'

def clean(args):
    """cleans the GraalVM source tree"""
    mx.clean(args)
    os.environ.update(ARCH_DATA_MODEL='64', LANG='C', HOTSPOT_BUILD_JOBS='16')
    mx.run([mx.gmake_cmd(), 'clean'], cwd=join(_graal_home, 'make'))

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
        'safeadd': ['com.oracle.max.graal.examples.safeadd', 'com.oracle.max.graal.examples.safeadd.Main'],
        'vectorlib': ['com.oracle.max.graal.examples.vectorlib', 'com.oracle.max.graal.examples.vectorlib.Main'],
    }

    def run_example(verbose, project, mainClass):
        cp = mx.classpath(project)
        sharedArgs = ['-Xcomp', '-XX:CompileOnly=Main', mainClass]
        
        res = []
        mx.log("=== Server VM ===")
        printArg = '-XX:+PrintCompilation' if verbose else '-XX:-PrintCompilation'
        res.append(vm(['-cp', cp, printArg] + sharedArgs, vm="-server"))
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
    
    DaCapo options are distinguised from VM options by a '@' prefix.
    For example, '@--iterations @5' will pass '--iterations 5' to the
    DaCapo harness."""
    
    benchmarks = [
        'avrora',
        'batik',
        'eclipse',
        'fop',
        'h2',
        'jython',
        'luindex',
        'lusearch',
        'pmd',
        'sunflow',
        'tomcat',
        'tradebeans',
        'tradesoap',
        'xalan'
    ]
    
    dacapo = mx.check_get_env('DACAPO_CP')
    if not isfile(dacapo) or not dacapo.endswith('.jar'):
        mx.abort('Specified DaCapo jar file does not exist or is not a jar file: ' + dacapo)
            
    vmOpts = ['-Xms1g', '-Xmx2g', '-cp', dacapo]

    selected = []
    while len(args) != 0 and args[0][0] not in ['-', '@']:
        bm = args[0]
        del args[0]
        if bm not in benchmarks:
            mx.abort('unknown benchmark: ' + bm + '\nselect one of: ' + str(benchmarks))
        selected.append(bm)
    
    if len(selected) != 0:    
        benchmarks = selected
    
    # Extract DaCapo options
    dacapoArgs = [(arg[1:]) for arg in args if arg.startswith('@')]
    
    # The remainder are VM options 
    vmOpts += [arg for arg in args if not arg.startswith('@')]

    dacapoSuccess = re.compile(r"^===== DaCapo 9\.12 ([a-zA-Z0-9_]+) PASSED in ([0-9]+) msec =====$")
    passed = []
        
    for bm in benchmarks:
        def errFilter(line):
            if dacapoSuccess.match(line):
                passed.append(bm)
            sys.stderr.write(line)
        vm(vmOpts + ['Harness'] + dacapoArgs + [bm], err=errFilter)
        
    failed = list(set(benchmarks).difference(set(passed)))
    
    if len(failed) != 0:
        mx.abort('Benchmark failures: ' + str(failed))
 
def _jdk(build='product', create=False):
    """
    Get the JDK into which Graal is installed, creating it first if necessary.
    """
    jdk = join(_graal_home, 'jdk' + mx.java().version)
    if not exists(jdk):
        srcJdk = mx.java().jdk
        mx.log('Creating ' + jdk + ' from ' + srcJdk)
        os.mkdir(jdk)
        for d in ['bin', 'db', 'include', 'jre', 'lib', 'man']:
            src = join(srcJdk, d)
            dst = join(jdk, d)
            if not exists(src):
                mx.abort('Host JDK directory is missing: ' + src)
            shutil.copytree(src, dst)
    
    jvmCfg = join(jdk, 'jre', 'lib', 'amd64', 'jvm.cfg')
    found = False
    if not exists(jvmCfg):
        mx.abort(jvmCfg + ' does not exist')
        
    with open(jvmCfg) as f:
        for line in f:
            if '-graal KNOWN' in line:
                found = True
                break
    if not found:
        mx.log('Appending "-graal KNOWN" to ' + jvmCfg)
        with open(jvmCfg, 'a') as f:
            f.write('-graal KNOWN\n')
    
    if build == 'product':
        return jdk
    elif build in ['debug', 'fastdebug', 'optimized']:
        res = join(jdk, build)
        if not exists(res):
            if not create:
                mx.abort('The ' + build + ' VM has not been created - run \'mx clean; mx make ' + build + '\'') 
            mx.log('Creating ' + res)
            os.mkdir(res)
            for d in ['bin', 'db', 'include', 'jre', 'lib', 'man']:
                shutil.copytree(join(jdk, d), join(res, d))
        return res
    else:
        mx.abort('Unknown build type: ' + build)
    
def build(args):
    """builds the GraalVM binary and compiles the Graal classes
    
    The optional argument specifies what type of VM to build."""

    build = 'product'
    if len(args) != 0 and not args[0].startswith('-'):
        build = args.pop(0)

    # Call mx.build to compile the Java sources        
    mx.build(args + ['--source', '1.7'])

    if not _vmSourcesAvailable:
        return
        
    jdk = _jdk(build, True)
    if build == 'debug':
        build = 'jvmg'
    
    graalVmDir = join(jdk, 'jre', 'lib', 'amd64', 'graal')
    if not exists(graalVmDir):
        mx.log('Creating Graal directory in JDK7: ' + graalVmDir)
        os.makedirs(graalVmDir)

    def filterXusage(line):
        if not 'Xusage.txt' in line:
            sys.stderr.write(line + os.linesep)
            
    os.environ.update(ARCH_DATA_MODEL='64', LANG='C', HOTSPOT_BUILD_JOBS='3', ALT_BOOTDIR=jdk, INSTALL='y')
    mx.run([mx.gmake_cmd(), build + 'graal'], cwd=join(_graal_home, 'make'), err=filterXusage)
    
def vm(args, vm='-graal', nonZeroIsFatal=True, out=None, err=None, cwd=None):
    """run the GraalVM"""
  
    build = _vmbuild if _vmSourcesAvailable else 'product'
    if mx.java().debug:
        args = ['-Xdebug', '-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000'] + args
    exe = join(_jdk(build), 'bin', mx.exe_suffix('java'))
    return mx.run([exe, vm] + args, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

def ideinit(args):
    """(re)generate Eclipse project configurations"""


    def println(out, obj):
        out.write(str(obj) + '\n')
        
    for p in mx.projects():
        if p.native:
            continue
        
        if not exists(p.dir):
            os.makedirs(p.dir)

        out = StringIO.StringIO()
        
        println(out, '<?xml version="1.0" encoding="UTF-8"?>')
        println(out, '<classpath>')
        for src in p.srcDirs:
            srcDir = join(p.dir, src)
            if not exists(srcDir):
                os.mkdir(srcDir)
            println(out, '\t<classpathentry kind="src" path="' + src + '"/>')
    
        # Every Java program depends on the JRE
        println(out, '\t<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>')
        
        for dep in p.all_deps([], True):
            if dep == p:
                continue;
            
            if dep.isLibrary():
                if hasattr(dep, 'eclipse.container'):
                    println(out, '\t<classpathentry exported="true" kind="con" path="' + getattr(dep, 'eclipse.container') + '"/>')
                elif hasattr(dep, 'eclipse.project'):
                    println(out, '\t<classpathentry combineaccessrules="false" exported="true" kind="src" path="/' + getattr(dep, 'eclipse.project') + '"/>')
                else:
                    path = dep.path
                    if dep.mustExist:
                        if isabs(path):
                            println(out, '\t<classpathentry exported="true" kind="lib" path="' + path + '"/>')
                        else:
                            println(out, '\t<classpathentry exported="true" kind="lib" path="/' + path + '"/>')
            else:
                println(out, '\t<classpathentry combineaccessrules="false" exported="true" kind="src" path="/' + dep.name + '"/>')
                        
        println(out, '\t<classpathentry kind="output" path="' + getattr(p, 'eclipse.output', 'bin') + '"/>')
        println(out, '</classpath>')
        mx.update_file(join(p.dir, '.classpath'), out.getvalue())
        out.close()

        csConfig = join(mx.project(p.checkstyleProj).dir, '.checkstyle_checks.xml')
        if exists(csConfig):
            out = StringIO.StringIO()
            
            dotCheckstyle = join(p.dir, ".checkstyle")
            checkstyleConfigPath = '/' + p.checkstyleProj + '/.checkstyle_checks.xml'
            println(out, '<?xml version="1.0" encoding="UTF-8"?>')
            println(out, '<fileset-config file-format-version="1.2.0" simple-config="true">')
            println(out, '\t<local-check-config name="Graal Checks" location="' + checkstyleConfigPath + '" type="project" description="">')
            println(out, '\t\t<additional-data name="protect-config-file" value="false"/>')
            println(out, '\t</local-check-config>')
            println(out, '\t<fileset name="all" enabled="true" check-config-name="Graal Checks" local="true">')
            println(out, '\t\t<file-match-pattern match-pattern="." include-pattern="true"/>')
            println(out, '\t</fileset>')
            println(out, '\t<filter name="FileTypesFilter" enabled="true">')
            println(out, '\t\t<filter-data value="java"/>')
            println(out, '\t</filter>')

            exclude = join(p.dir, '.checkstyle.exclude')
            if exists(exclude):
                println(out, '\t<filter name="FilesFromPackage" enabled="true">')
                with open(exclude) as f:
                    for line in f:
                        if not line.startswith('#'):
                            line = line.strip()
                            exclDir = join(p.dir, line)
                            assert isdir(exclDir), 'excluded source directory listed in ' + exclude + ' does not exist or is not a directory: ' + exclDir
                        println(out, '\t\t<filter-data value="' + line + '"/>')
                println(out, '\t</filter>')
                        
            println(out, '</fileset-config>')
            mx.update_file(dotCheckstyle, out.getvalue())
            out.close()
        

        out = StringIO.StringIO()
        
        println(out, '<?xml version="1.0" encoding="UTF-8"?>')
        println(out, '<projectDescription>')
        println(out, '\t<name>' + p.name + '</name>')
        println(out, '\t<comment></comment>')
        println(out, '\t<projects>')
        println(out, '\t</projects>')
        println(out, '\t<buildSpec>')
        println(out, '\t\t<buildCommand>')
        println(out, '\t\t\t<name>org.eclipse.jdt.core.javabuilder</name>')
        println(out, '\t\t\t<arguments>')
        println(out, '\t\t\t</arguments>')
        println(out, '\t\t</buildCommand>')
        if exists(csConfig):
            println(out, '\t\t<buildCommand>')
            println(out, '\t\t\t<name>net.sf.eclipsecs.core.CheckstyleBuilder</name>')
            println(out, '\t\t\t<arguments>')
            println(out, '\t\t\t</arguments>')
            println(out, '\t\t</buildCommand>')
        println(out, '\t</buildSpec>')
        println(out, '\t<natures>')
        println(out, '\t\t<nature>org.eclipse.jdt.core.javanature</nature>')
        if exists(csConfig):
            println(out, '\t\t<nature>net.sf.eclipsecs.core.CheckstyleNature</nature>')
        println(out, '\t</natures>')
        println(out, '</projectDescription>')
        mx.update_file(join(p.dir, '.project'), out.getvalue())
        out.close()

        out = StringIO.StringIO()
        settingsDir = join(p.dir, ".settings")
        if not exists(settingsDir):
            os.mkdir(settingsDir)

        myDir = dirname(__file__)
        
        with open(join(myDir, 'org.eclipse.jdt.core.prefs')) as f:
            content = f.read()
        mx.update_file(join(settingsDir, 'org.eclipse.jdt.core.prefs'), content)
            
        with open(join(myDir, 'org.eclipse.jdt.ui.prefs')) as f:
            content = f.read()
        mx.update_file(join(settingsDir, 'org.eclipse.jdt.ui.prefs'), content)

def mx_init():
    _vmbuild = 'product'
    commands = {
        'clean': [clean, ''],
        'build': [build, ''],
        'dacapo': [dacapo, '[benchmark] [VM options|DaCapo options]'],
        'example': [example, '[-v] example names...'],
        'vm': [vm, '[-options] class [args...]'],
        'ideinit': [ideinit, '']
    }

    if (_vmSourcesAvailable):
        mx.add_argument('--product', action='store_const', dest='vmbuild', const='product', help='select the product VM')
        mx.add_argument('--debug', action='store_const', dest='vmbuild', const='debug', help='select the debug VM')
        mx.add_argument('--fastdebug', action='store_const', dest='vmbuild', const='fastdebug', help='select the fast debug VM')
        mx.add_argument('--optimized', action='store_const', dest='vmbuild', const='optimized', help='select the optimized VM')
        
        commands.update({
            'export': [export, '[-options] [zipfile]'],
            'build': [build, '[product|debug|fastdebug|optimized]']
        })
    
    mx.commands.update(commands)

def mx_post_parse_cmd_line(opts):
    version = mx.java().version
    parts = version.split('.')
    assert len(parts) >= 2
    assert parts[0] == '1'
    major = int(parts[1])
    if not major >= 7:
        mx.abort('Requires Java version 1.7 or greater, got version ' + version)

    if (_vmSourcesAvailable):
        global _vmbuild
        if not opts.vmbuild is None:
            _vmbuild = opts.vmbuild
