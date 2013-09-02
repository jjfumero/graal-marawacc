#!/usr/bin/python
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
#

r"""
mx is a command line tool inspired by mvn (http://maven.apache.org/)
and hg (http://mercurial.selenic.com/). It includes a mechanism for
managing the dependencies between a set of projects (like Maven)
as well as making it simple to run commands
(like hg is the interface to the Mercurial commands).

The organizing principle of mx is a project suite. A suite is a directory
containing one or more projects. It's not coincidental that this closely
matches the layout of one or more projects in a Mercurial repository.
The configuration information for a suite lives in an 'mx' sub-directory
at the top level of the suite.

When launched, mx treats the current working directory as a suite.
This is the primary suite. All other suites are called included suites.

The configuration files (i.e. in the 'mx' sub-directory) of a suite are:

  projects
      Defines the projects and libraries in the suite and the
      dependencies between them.

  commands.py
      Suite specific extensions to the commands available to mx.

  includes
      Other suites to be loaded. This is recursive. Each
      line in an includes file is a path to a suite directory.

  env
      A set of environment variable definitions. These override any
      existing environment variables. Common properties set here
      include JAVA_HOME and IGNORED_PROJECTS.

The includes and env files are typically not put under version control
as they usually contain local file-system paths.

The projects file is like the pom.xml file from Maven except that
it is a properties file (not XML). Each non-comment line
in the file specifies an attribute of a project or library. The main
difference between a project and a library is that the former contains
source code built by the mx tool where as the latter is an external
dependency. The format of the projects file is

Library specification format:

    library@<name>@<prop>=<value>

Built-in library properties (* = required):

   *path
        The file system path for the library to appear on a class path.

    urls
        A comma separated list of URLs from which the library (jar) can
        be downloaded and saved in the location specified by 'path'.

    optional
        If "true" then this library will be omitted from a class path
        if it doesn't exist on the file system and no URLs are specified.

    sourcePath
        The file system path for a jar file containing the library sources.

    sourceUrls
        A comma separated list of URLs from which the library source jar can
        be downloaded and saved in the location specified by 'sourcePath'.

Project specification format:

    project@<name>@<prop>=<value>

The name of a project also denotes the directory it is in.

Built-in project properties (* = required):

    subDir
        The sub-directory of the suite in which the project directory is
        contained. If not specified, the project directory is directly
        under the suite directory.

   *sourceDirs
        A comma separated list of source directory names (relative to
        the project directory).

    dependencies
        A comma separated list of the libraries and project the project
        depends upon (transitive dependencies should be omitted).

    checkstyle
        The project whose Checkstyle configuration
        (i.e. <project>/.checkstyle_checks.xml) is used.

    native
        "true" if the project is native.

    javaCompliance
        The minimum JDK version (format: x.y) to which the project's
        sources comply (required for non-native projects).
    
    workingSets
        A comma separated list of working set names. The project belongs
        to the given working sets, for which the eclipseinit command
        will generate Eclipse configurations.

Other properties can be specified for projects and libraries for use
by extension commands.

Property values can use environment variables with Bash syntax (e.g. ${HOME}).
"""

import sys, os, errno, time, subprocess, shlex, types, urllib2, contextlib, StringIO, zipfile, signal, xml.sax.saxutils, tempfile
import textwrap
import xml.parsers.expat
import shutil, re, xml.dom.minidom
from collections import Callable
from threading import Thread
from argparse import ArgumentParser, REMAINDER
from os.path import join, basename, dirname, exists, getmtime, isabs, expandvars, isdir, isfile

DEFAULT_JAVA_ARGS = '-ea -Xss2m -Xmx1g'

_projects = dict()
_libs = dict()
_dists = dict()
_suites = dict()
_annotationProcessors = None
_mainSuite = None
_opts = None
_java = None

"""
A distribution is a jar or zip file containing the output from one or more Java projects.
"""
class Distribution:
    def __init__(self, suite, name, path, deps):
        self.suite = suite
        self.name = name
        self.path = path.replace('/', os.sep)
        if not isabs(self.path):
            self.path = join(suite.dir, self.path)
        self.deps = deps
        self.update_listeners = set()
        
    def __str__(self):
        return self.name
    
    def add_update_listener(self, listener):
        self.update_listeners.add(listener)
        
    def notify_updated(self):
        for l in self.update_listeners:
            l(self)
    
"""
A dependency is a library or project specified in a suite.
"""
class Dependency:
    def __init__(self, suite, name):
        self.name = name
        self.suite = suite

    def __str__(self):
        return self.name

    def __eq__(self, other):
        return self.name == other.name

    def __ne__(self, other):
        return self.name != other.name

    def __hash__(self):
        return hash(self.name)

    def isLibrary(self):
        return isinstance(self, Library)
    
    def isProject(self):
        return isinstance(self, Project)

class Project(Dependency):
    def __init__(self, suite, name, srcDirs, deps, javaCompliance, workingSets, d):
        Dependency.__init__(self, suite, name)
        self.srcDirs = srcDirs
        self.deps = deps
        self.checkstyleProj = name
        self.javaCompliance = JavaCompliance(javaCompliance) if javaCompliance is not None else None
        self.native = False
        self.workingSets = workingSets
        self.dir = d
        
        # Create directories for projects that don't yet exist
        if not exists(d):
            os.mkdir(d)
        for s in self.source_dirs():
            if not exists(s):
                os.mkdir(s)

    def all_deps(self, deps, includeLibs, includeSelf=True, includeAnnotationProcessors=False):
        """
        Add the transitive set of dependencies for this project, including
        libraries if 'includeLibs' is true, to the 'deps' list.
        """
        childDeps = list(self.deps)
        if includeAnnotationProcessors and len(self.annotation_processors()) > 0:
            childDeps = self.annotation_processors() + childDeps
        if self in deps:
            return deps
        for name in childDeps:
            assert name != self.name
            dep = dependency(name)
            if not dep in deps and (includeLibs or not dep.isLibrary()):
                dep.all_deps(deps, includeLibs=includeLibs, includeAnnotationProcessors=includeAnnotationProcessors)
        if not self in deps and includeSelf:
            deps.append(self)
        return deps

    def _compute_max_dep_distances(self, name, distances, dist):
        currentDist = distances.get(name);
        if currentDist is None or currentDist < dist:
            distances[name] = dist
            p = project(name, False)
            if p is not None:
                for dep in p.deps:
                    self._compute_max_dep_distances(dep, distances, dist + 1)

    def canonical_deps(self):
        """
        Get the dependencies of this project that are not recursive (i.e. cannot be reached
        via other dependencies).
        """
        distances = dict()
        result = set()
        self._compute_max_dep_distances(self.name, distances, 0)
        for n,d in distances.iteritems():
            assert d > 0 or n == self.name
            if d == 1:
                result.add(n)

        if len(result) == len(self.deps) and frozenset(self.deps) == result:
            return self.deps
        return result;

    def max_depth(self):
        """
        Get the maximum canonical distance between this project and its most distant dependency.
        """
        distances = dict()
        self._compute_max_dep_distances(self.name, distances, 0)
        return max(distances.values())        

    def source_dirs(self):
        """
        Get the directories in which the sources of this project are found.
        """
        return [join(self.dir, s) for s in self.srcDirs]

    def source_gen_dir(self):
        """
        Get the directory in which source files generated by the annotation processor are found/placed.
        """
        if self.native:
            return None
        return join(self.dir, 'src_gen')

    def output_dir(self):
        """
        Get the directory in which the class files of this project are found/placed.
        """
        if self.native:
            return None
        return join(self.dir, 'bin')

    def jasmin_output_dir(self):
        """
        Get the directory in which the Jasmin assembled class files of this project are found/placed.
        """
        if self.native:
            return None
        return join(self.dir, 'jasmin_classes')

    def append_to_classpath(self, cp, resolve):
        if not self.native:
            cp.append(self.output_dir())

    def find_classes_with_matching_source_line(self, pkgRoot, function, includeInnerClasses=False):
        """
        Scan the sources of this project for Java source files containing a line for which
        'function' returns true. A map from class name to source file path for each existing class
        corresponding to a matched source file is returned.
        """
        result = dict()
        pkgDecl = re.compile(r"^package\s+([a-zA-Z_][\w\.]*)\s*;$")
        for srcDir in self.source_dirs():
            outputDir = self.output_dir()
            for root, _, files in os.walk(srcDir):
                for name in files:
                    if name.endswith('.java') and name != 'package-info.java':
                        matchFound = False
                        source = join(root, name)
                        with open(source) as f:
                            pkg = None
                            for line in f:
                                if line.startswith("package "):
                                    match = pkgDecl.match(line)
                                    if match:
                                        pkg = match.group(1)
                                if function(line.strip()):
                                    matchFound = True
                                if pkg and matchFound:
                                    break
                                        
                        if matchFound:
                            basename = name[:-len('.java')]
                            assert pkg is not None
                            if pkgRoot is None or pkg.startswith(pkgRoot):
                                pkgOutputDir = join(outputDir, pkg.replace('.', os.path.sep))
                                if exists(pkgOutputDir):
                                    for e in os.listdir(pkgOutputDir):
                                        if includeInnerClasses:
                                            if e.endswith('.class') and (e.startswith(basename) or e.startswith(basename + '$')):
                                                className = pkg + '.' + e[:-len('.class')]
                                                result[className] = source
                                        elif e == basename + '.class':
                                            className = pkg + '.' + basename
                                            result[className] = source
        return result
    
    def _init_packages_and_imports(self):
        if not hasattr(self, '_defined_java_packages'):
            packages = set()
            extendedPackages = set()
            depPackages = set()
            for d in self.all_deps([], includeLibs=False, includeSelf=False):
                depPackages.update(d.defined_java_packages())
            imports = set()
            importRe = re.compile(r'import\s+(?:static\s+)?([^;]+);')
            for sourceDir in self.source_dirs():
                for root, _, files in os.walk(sourceDir):
                    javaSources = [name for name in files if name.endswith('.java')]
                    if len(javaSources) != 0:
                        pkg = root[len(sourceDir) + 1:].replace(os.sep,'.')
                        if not pkg in depPackages:
                            packages.add(pkg)
                        else:
                            # A project extends a package already defined by one of it dependencies
                            extendedPackages.add(pkg)
                            imports.add(pkg)
                        
                        for n in javaSources:
                            with open(join(root, n)) as fp:
                                content = fp.read()
                                imports.update(importRe.findall(content))
            self._defined_java_packages = frozenset(packages)
            self._extended_java_packages = frozenset(extendedPackages)
            
            importedPackages = set()
            for imp in imports:
                name = imp
                while not name in depPackages and len(name) > 0:
                    lastDot = name.rfind('.')
                    if lastDot == -1:
                        name = None
                        break
                    name = name[0:lastDot]
                if name is not None:
                    importedPackages.add(name)
            self._imported_java_packages = frozenset(importedPackages)
    
    def defined_java_packages(self):
        """Get the immutable set of Java packages defined by the Java sources of this project"""
        self._init_packages_and_imports()
        return self._defined_java_packages
    
    def extended_java_packages(self):
        """Get the immutable set of Java packages extended by the Java sources of this project"""
        self._init_packages_and_imports()
        return self._extended_java_packages

    def imported_java_packages(self):
        """Get the immutable set of Java packages defined by other Java projects that are
           imported by the Java sources of this project."""
        self._init_packages_and_imports()
        return self._imported_java_packages

    def annotation_processors(self):
        if not hasattr(self, '_annotationProcessors'):
            ap = set()
            if hasattr(self, '_declaredAnnotationProcessors'):
                ap = set(self._declaredAnnotationProcessors)

            # find dependencies that auto-inject themselves as annotation processors to all dependents                
            allDeps = self.all_deps([], includeLibs=False, includeSelf=False, includeAnnotationProcessors=False)
            for p in allDeps:
                if hasattr(p, 'annotationProcessorForDependents') and p.annotationProcessorForDependents.lower() == 'true':
                    ap.add(p.name)
            self._annotationProcessors = list(ap)
        return self._annotationProcessors

class Library(Dependency):
    def __init__(self, suite, name, path, mustExist, urls, sourcePath, sourceUrls):
        Dependency.__init__(self, suite, name)
        self.path = path.replace('/', os.sep)
        self.urls = urls
        self.mustExist = mustExist
        self.sourcePath = sourcePath
        self.sourceUrls = sourceUrls
        for url in urls:
            if url.endswith('/') != self.path.endswith(os.sep):
                abort('Path for dependency directory must have a URL ending with "/": path=' + self.path + ' url=' + url)

    def get_path(self, resolve):
        path = self.path
        if not isabs(path):
            path = join(self.suite.dir, path)
        if resolve and self.mustExist and not exists(path):
            assert not len(self.urls) == 0, 'cannot find required library ' + self.name + ' ' + path;
            print('Downloading ' + self.name + ' from ' + str(self.urls))
            download(path, self.urls)
        return path

    def get_source_path(self, resolve):
        path = self.sourcePath
        if path is None:
            return None
        if not isabs(path):
            path = join(self.suite.dir, path)
        if resolve and len(self.sourceUrls) != 0 and not exists(path):
            print('Downloading sources for ' + self.name + ' from ' + str(self.sourceUrls))
            download(path, self.sourceUrls)
        return path

    def append_to_classpath(self, cp, resolve):
        path = self.get_path(resolve)
        if exists(path) or not resolve:
            cp.append(path)
            
    def all_deps(self, deps, includeLibs, includeSelf=True, includeAnnotationProcessors=False):
        if not includeLibs or not includeSelf:
            return deps
        deps.append(self)
        return deps

class Suite:
    def __init__(self, d, primary):
        self.dir = d
        self.projects = []
        self.libs = []
        self.dists = []
        self.includes = []
        self.commands = None
        self.primary = primary
        mxDir = join(d, 'mx')
        self._load_env(mxDir)
        self._load_commands(mxDir)
        self._load_includes(mxDir)
        self.name = d # re-initialized in _load_projects

    def __str__(self):
        return self.name
    
    def _load_projects(self, mxDir):
        libsMap = dict()
        projsMap = dict()
        distsMap = dict()
        projectsFile = join(mxDir, 'projects')
        if not exists(projectsFile):
            return
        with open(projectsFile) as f:
            for line in f:
                line = line.strip()
                if len(line) != 0 and line[0] != '#':
                    key, value = line.split('=', 1)

                    parts = key.split('@')

                    if len(parts) == 1:
                        if parts[0] != 'suite':
                            abort('Single part property must be "suite": ' + key)
                        self.name = value
                        continue
                    if len(parts) != 3:
                        abort('Property name does not have 3 parts separated by "@": ' + key)
                    kind, name, attr = parts
                    if kind == 'project':
                        m = projsMap
                    elif kind == 'library':
                        m = libsMap
                    elif kind == 'distribution':
                        m = distsMap
                    else:
                        abort('Property name does not start with "project@", "library@" or "distribution@": ' + key)

                    attrs = m.get(name)
                    if attrs is None:
                        attrs = dict()
                        m[name] = attrs
                    value = expandvars_in_property(value)
                    attrs[attr] = value

        def pop_list(attrs, name):
            v = attrs.pop(name, None)
            if v is None or len(v.strip()) == 0:
                return []
            return [n.strip() for n in v.split(',')]

        for name, attrs in projsMap.iteritems():
            srcDirs = pop_list(attrs, 'sourceDirs')
            deps = pop_list(attrs, 'dependencies')
            ap = pop_list(attrs, 'annotationProcessors')
            #deps += ap
            javaCompliance = attrs.pop('javaCompliance', None)
            subDir = attrs.pop('subDir', None)
            if subDir is None:
                d = join(self.dir, name)
            else:
                d = join(self.dir, subDir, name)
            workingSets = attrs.pop('workingSets', None)
            p = Project(self, name, srcDirs, deps, javaCompliance, workingSets, d)
            p.checkstyleProj = attrs.pop('checkstyle', name)
            p.native = attrs.pop('native', '') == 'true'
            if not p.native and p.javaCompliance is None:
                abort('javaCompliance property required for non-native project ' + name)
            if len(ap) > 0:
                p._declaredAnnotationProcessors = ap
            p.__dict__.update(attrs)
            self.projects.append(p)

        for name, attrs in libsMap.iteritems():
            path = attrs.pop('path')
            mustExist = attrs.pop('optional', 'false') != 'true'
            urls = pop_list(attrs, 'urls')
            sourcePath = attrs.pop('sourcePath', None)
            sourceUrls = pop_list(attrs, 'sourceUrls')
            l = Library(self, name, path, mustExist, urls, sourcePath, sourceUrls)
            l.__dict__.update(attrs)
            self.libs.append(l)

        for name, attrs in distsMap.iteritems():
            path = attrs.pop('path')
            deps = pop_list(attrs, 'dependencies')
            d = Distribution(self, name, path, deps)
            d.__dict__.update(attrs)
            self.dists.append(d)
            
        if self.name is None:
            abort('Missing "suite=<name>" in ' + projectsFile)

    def _load_commands(self, mxDir):
        commands = join(mxDir, 'commands.py')
        if exists(commands):
            # temporarily extend the Python path
            sys.path.insert(0, mxDir)
            mod = __import__('commands')
            
            self.commands = sys.modules.pop('commands')
            sys.modules[join(mxDir, 'commands')] = self.commands

            # revert the Python path
            del sys.path[0]

            if not hasattr(mod, 'mx_init'):
                abort(commands + ' must define an mx_init(env) function')
            if hasattr(mod, 'mx_post_parse_cmd_line'):
                self.mx_post_parse_cmd_line = mod.mx_post_parse_cmd_line

            mod.mx_init()
            self.commands = mod

    def _load_includes(self, mxDir):
        includes = join(mxDir, 'includes')
        if exists(includes):
            with open(includes) as f:
                for line in f:
                    include = expandvars_in_property(line.strip())
                    self.includes.append(include)
                    _loadSuite(include, False)

    def _load_env(self, mxDir):
        e = join(mxDir, 'env')
        if exists(e):
            with open(e) as f:
                lineNum = 0
                for line in f:
                    lineNum = lineNum + 1
                    line = line.strip()
                    if len(line) != 0 and line[0] != '#':
                        if not '=' in line:
                            abort(e + ':' + str(lineNum) + ': line does not match pattern "key=value"')
                        key, value = line.split('=', 1)
                        os.environ[key.strip()] = expandvars_in_property(value.strip())
    def _post_init(self, opts):
        mxDir = join(self.dir, 'mx')
        self._load_projects(mxDir)
        for p in self.projects:
            existing = _projects.get(p.name)
            if existing is not None:
                abort('cannot override project  ' + p.name + ' in ' + p.dir + " with project of the same name in  " + existing.dir)
            if not p.name in _opts.ignored_projects:
                _projects[p.name] = p
        for l in self.libs:
            existing = _libs.get(l.name)
            if existing is not None:
                abort('cannot redefine library  ' + l.name)
            _libs[l.name] = l
        for d in self.dists:
            existing = _dists.get(l.name)
            if existing is not None:
                abort('cannot redefine distribution  ' + d.name)
            _dists[d.name] = d
        if hasattr(self, 'mx_post_parse_cmd_line'):
            self.mx_post_parse_cmd_line(opts)

class XMLElement(xml.dom.minidom.Element):
    def writexml(self, writer, indent="", addindent="", newl=""):
        writer.write(indent+"<" + self.tagName)

        attrs = self._get_attributes()
        a_names = attrs.keys()
        a_names.sort()

        for a_name in a_names:
            writer.write(" %s=\"" % a_name)
            xml.dom.minidom._write_data(writer, attrs[a_name].value)
            writer.write("\"")
        if self.childNodes:
            if not self.ownerDocument.padTextNodeWithoutSiblings and len(self.childNodes) == 1 and isinstance(self.childNodes[0], xml.dom.minidom.Text):
                # if the only child of an Element node is a Text node, then the
                # text is printed without any indentation or new line padding
                writer.write(">")
                self.childNodes[0].writexml(writer)
                writer.write("</%s>%s" % (self.tagName,newl))
            else:
                writer.write(">%s"%(newl))
                for node in self.childNodes:
                    node.writexml(writer,indent+addindent,addindent,newl)
                writer.write("%s</%s>%s" % (indent,self.tagName,newl))
        else:
            writer.write("/>%s"%(newl))

class XMLDoc(xml.dom.minidom.Document):

    def __init__(self):
        xml.dom.minidom.Document.__init__(self)
        self.current = self
        self.padTextNodeWithoutSiblings = False

    def createElement(self, tagName):
        # overwritten to create XMLElement
        e = XMLElement(tagName)
        e.ownerDocument = self
        return e

    def comment(self, txt):
        self.current.appendChild(self.createComment(txt))

    def open(self, tag, attributes={}, data=None):
        element = self.createElement(tag)
        for key, value in attributes.items():
            element.setAttribute(key, value)
        self.current.appendChild(element)
        self.current = element
        if data is not None:
            element.appendChild(self.createTextNode(data))
        return self

    def close(self, tag):
        assert self.current != self
        assert tag == self.current.tagName, str(tag) + ' != ' + self.current.tagName
        self.current = self.current.parentNode
        return self

    def element(self, tag, attributes={}, data=None):
        return self.open(tag, attributes, data).close(tag)

    def xml(self, indent='', newl='', escape=False, standalone=None):
        assert self.current == self
        result = self.toprettyxml(indent, newl, encoding="UTF-8")
        if escape:
            entities = { '"':  "&quot;", "'":  "&apos;", '\n': '&#10;' }
            result = xml.sax.saxutils.escape(result, entities)
        if standalone is not None:
            result = result.replace('encoding="UTF-8"?>', 'encoding="UTF-8" standalone="' + str(standalone) + '"?>')
        return result

def get_os():
    """
    Get a canonical form of sys.platform.
    """
    if sys.platform.startswith('darwin'):
        return 'darwin'
    elif sys.platform.startswith('linux'):
        return 'linux'
    elif sys.platform.startswith('sunos'):
        return 'solaris'
    elif sys.platform.startswith('win32') or sys.platform.startswith('cygwin'):
        return 'windows'
    else:
        abort('Unknown operating system ' + sys.platform)

def _loadSuite(d, primary=False):
    mxDir = join(d, 'mx')
    if not exists(mxDir) or not isdir(mxDir):
        return None
    if len([s for s in _suites.itervalues() if s.dir == d]) == 0:
        s = Suite(d, primary)
        _suites[s.name] = s
        return s

def suites():
    """
    Get the list of all loaded suites.
    """
    return _suites.values()

def suite(name, fatalIfMissing=True):
    """
    Get the suite for a given name.
    """
    s = _suites.get(name)
    if s is None and fatalIfMissing:
        abort('suite named ' + name + ' not found')
    return s

def projects():
    """
    Get the list of all loaded projects.
    """
    return _projects.values()

def annotation_processors():
    """
    Get the list of all loaded projects that define an annotation processor.
    """
    global _annotationProcessors
    if _annotationProcessors is None:
        aps = set()
        for p in projects():
            for ap in p.annotation_processors():
                if project(ap, False):
                    aps.add(ap)
        _annotationProcessors = list(aps)
    return _annotationProcessors

def distribution(name, fatalIfMissing=True):
    """
    Get the distribution for a given name. This will abort if the named distribution does
    not exist and 'fatalIfMissing' is true.
    """
    d = _dists.get(name)
    if d is None and fatalIfMissing:
        abort('distribution named ' + name + ' not found')
    return d

def dependency(name, fatalIfMissing=True):
    """
    Get the project or library for a given name. This will abort if a project  or library does
    not exist for 'name' and 'fatalIfMissing' is true.
    """
    d = _projects.get(name)
    if d is None:
        d = _libs.get(name)
    if d is None and fatalIfMissing:
        if name in _opts.ignored_projects:
            abort('project named ' + name + ' is ignored')
        abort('project or library named ' + name + ' not found')
    return d

def project(name, fatalIfMissing=True):
    """
    Get the project for a given name. This will abort if the named project does
    not exist and 'fatalIfMissing' is true.
    """
    p = _projects.get(name)
    if p is None and fatalIfMissing:
        if name in _opts.ignored_projects:
            abort('project named ' + name + ' is ignored')
        abort('project named ' + name + ' not found')
    return p

def library(name, fatalIfMissing=True):
    """
    Gets the library for a given name. This will abort if the named library does
    not exist and 'fatalIfMissing' is true.
    """
    l = _libs.get(name)
    if l is None and fatalIfMissing:
        abort('library named ' + name + ' not found')
    return l

def _as_classpath(deps, resolve):
    cp = []
    if _opts.cp_prefix is not None:
        cp = [_opts.cp_prefix]
    for d in deps:
        d.append_to_classpath(cp, resolve)
    if _opts.cp_suffix is not None:
        cp += [_opts.cp_suffix]
    return os.pathsep.join(cp)

def classpath(names=None, resolve=True, includeSelf=True, includeBootClasspath=False):
    """
    Get the class path for a list of given dependencies, resolving each entry in the
    path (e.g. downloading a missing library) if 'resolve' is true.
    """
    if names is None:
        result = _as_classpath(sorted_deps(includeLibs=True), resolve)
    else:
        deps = []
        if isinstance(names, types.StringTypes):
            names = [names]
        for n in names:
            dependency(n).all_deps(deps, True, includeSelf)
        result = _as_classpath(deps, resolve)
    if includeBootClasspath:
        result = os.pathsep.join([java().bootclasspath(), result])
    return result

def classpath_walk(names=None, resolve=True, includeSelf=True, includeBootClasspath=False):
    """
    Walks the resources available in a given classpath, yielding a tuple for each resource
    where the first member of the tuple is a directory path or ZipFile object for a
    classpath entry and the second member is the qualified path of the resource relative
    to the classpath entry.
    """
    cp = classpath(names, resolve, includeSelf, includeBootClasspath)
    for entry in cp.split(os.pathsep):
        if not exists(entry):
            continue
        if isdir(entry):
            for root, dirs, files in os.walk(entry):
                for d in dirs:
                    entryPath = join(root[len(entry) + 1:], d)
                    yield entry, entryPath
                for f in files:
                    entryPath = join(root[len(entry) + 1:], f)
                    yield entry, entryPath
        elif entry.endswith('.jar') or entry.endswith('.zip'):
            with zipfile.ZipFile(entry, 'r') as zf:
                for zi in zf.infolist():
                    entryPath = zi.filename
                    yield zf, entryPath

def sorted_deps(projectNames=None, includeLibs=False, includeAnnotationProcessors=False):
    """
    Gets projects and libraries sorted such that dependencies
    are before the projects that depend on them. Unless 'includeLibs' is
    true, libraries are omitted from the result.
    """
    deps = []
    if projectNames is None:
        projects = _projects.values()
    else:
        projects = [project(name) for name in projectNames]

    for p in projects:
        p.all_deps(deps, includeLibs=includeLibs, includeAnnotationProcessors=includeAnnotationProcessors)
    return deps

def _handle_missing_java_home():
    if not sys.stdout.isatty():
        abort('Could not find bootstrap JDK. Use --java-home option or ensure JAVA_HOME environment variable is set.')
 
    candidateJdks = []
    if get_os() == 'darwin':
        base = '/Library/Java/JavaVirtualMachines'
        candidateJdks = [join(base, n, 'Contents/Home') for n in os.listdir(base) if exists(join(base, n, 'Contents/Home'))]
    elif get_os() == 'linux':
        base = '/usr/lib/jvm'
        candidateJdks = [join(base, n) for n in os.listdir(base) if exists(join(base, n, 'jre/lib/rt.jar'))]
    elif get_os() == 'solaris':
        base = '/usr/jdk/instances'
        candidateJdks = [join(base, n) for n in os.listdir(base) if exists(join(base, n, 'jre/lib/rt.jar'))]
    elif get_os() == 'windows':
        base = r'C:\Program Files\Java'
        candidateJdks = [join(base, n) for n in os.listdir(base) if exists(join(base, n, r'jre\lib\rt.jar'))]

    javaHome = None
    if len(candidateJdks) != 0:
        javaHome = select_items(candidateJdks + ['<other>'], allowMultiple=False)
        if javaHome == '<other>':
            javaHome = None
            
    while javaHome is None:
        javaHome = raw_input('Enter path of bootstrap JDK: ')
        rtJarPath = join(javaHome, 'jre', 'lib', 'rt.jar')
        if not exists(rtJarPath):
            log('Does not appear to be a valid JDK as ' + rtJarPath + ' does not exist')
            javaHome = None
        else:
            break
    
    envPath = join(_mainSuite.dir, 'mx', 'env')
    if ask_yes_no('Persist this setting by adding "JAVA_HOME=' + javaHome + '" to ' + envPath, 'y'):
        with open(envPath, 'a') as fp:
            print >> fp, 'JAVA_HOME=' + javaHome
            
    return javaHome

class ArgParser(ArgumentParser):

    # Override parent to append the list of available commands
    def format_help(self):
        return ArgumentParser.format_help(self) + _format_commands()


    def __init__(self):
        self.java_initialized = False
        ArgumentParser.__init__(self, prog='mx')

        self.add_argument('-v', action='store_true', dest='verbose', help='enable verbose output')
        self.add_argument('-V', action='store_true', dest='very_verbose', help='enable very verbose output')
        self.add_argument('--dbg', type=int, dest='java_dbg_port', help='make Java processes wait on <port> for a debugger', metavar='<port>')
        self.add_argument('-d', action='store_const', const=8000, dest='java_dbg_port', help='alias for "-dbg 8000"')
        self.add_argument('--cp-pfx', dest='cp_prefix', help='class path prefix', metavar='<arg>')
        self.add_argument('--cp-sfx', dest='cp_suffix', help='class path suffix', metavar='<arg>')
        self.add_argument('--J', dest='java_args', help='Java VM arguments (e.g. --J @-dsa)', metavar='@<args>', default=DEFAULT_JAVA_ARGS)
        self.add_argument('--Jp', action='append', dest='java_args_pfx', help='prefix Java VM arguments (e.g. --Jp @-dsa)', metavar='@<args>', default=[])
        self.add_argument('--Ja', action='append', dest='java_args_sfx', help='suffix Java VM arguments (e.g. --Ja @-dsa)', metavar='@<args>', default=[])
        self.add_argument('--user-home', help='users home directory', metavar='<path>', default=os.path.expanduser('~'))
        self.add_argument('--java-home', help='bootstrap JDK installation directory (must be JDK 6 or later)', metavar='<path>')
        self.add_argument('--ignore-project', action='append', dest='ignored_projects', help='name of project to ignore', metavar='<name>', default=[])
        if get_os() != 'windows':
            # Time outs are (currently) implemented with Unix specific functionality
            self.add_argument('--timeout', help='timeout (in seconds) for command', type=int, default=0, metavar='<secs>')
            self.add_argument('--ptimeout', help='timeout (in seconds) for subprocesses', type=int, default=0, metavar='<secs>')

    def _parse_cmd_line(self, args=None):
        if args is None:
            args = sys.argv[1:]

        self.add_argument('commandAndArgs', nargs=REMAINDER, metavar='command args...')

        opts = self.parse_args()

        # Give the timeout options a default value to avoid the need for hasattr() tests
        opts.__dict__.setdefault('timeout', 0)
        opts.__dict__.setdefault('ptimeout', 0)

        if opts.very_verbose:
            opts.verbose = True

        if opts.java_home is None:
            opts.java_home = os.environ.get('JAVA_HOME')

        if opts.java_home is None or opts.java_home == '':
            opts.java_home = _handle_missing_java_home()

        if opts.user_home is None or opts.user_home == '':
            abort('Could not find user home. Use --user-home option or ensure HOME environment variable is set.')

        os.environ['JAVA_HOME'] = opts.java_home
        os.environ['HOME'] = opts.user_home

        opts.ignored_projects = opts.ignored_projects + os.environ.get('IGNORED_PROJECTS', '').split(',')

        commandAndArgs = opts.__dict__.pop('commandAndArgs')
        return opts, commandAndArgs

def _format_commands():
    msg = '\navailable commands:\n\n'
    for cmd in sorted(commands.iterkeys()):
        c, _ = commands[cmd][:2]
        doc = c.__doc__
        if doc is None:
            doc = ''
        msg += ' {0:<20} {1}\n'.format(cmd, doc.split('\n', 1)[0])
    return msg + '\n'

def java():
    """
    Get a JavaConfig object containing Java commands launch details.
    """
    assert _java is not None
    return _java

def run_java(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    return run(java().format_cmd(args), nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

def _kill_process_group(pid):
    pgid = os.getpgid(pid)
    try:
        os.killpg(pgid, signal.SIGKILL)
        return True
    except:
        log('Error killing subprocess ' + str(pgid) + ': ' + str(sys.exc_info()[1]))
        return False

def _waitWithTimeout(process, args, timeout):
    def _waitpid(pid):
        while True:
            try:
                return os.waitpid(pid, os.WNOHANG)
            except OSError, e:
                if e.errno == errno.EINTR:
                    continue
                raise

    def _returncode(status):
        if os.WIFSIGNALED(status):
            return -os.WTERMSIG(status)
        elif os.WIFEXITED(status):
            return os.WEXITSTATUS(status)
        else:
            # Should never happen
            raise RuntimeError("Unknown child exit status!")

    end = time.time() + timeout
    delay = 0.0005
    while True:
        (pid, status) = _waitpid(process.pid)
        if pid == process.pid:
            return _returncode(status)
        remaining = end - time.time()
        if remaining <= 0:
            abort('Process timed out after {0} seconds: {1}'.format(timeout, ' '.join(args)))
        delay = min(delay * 2, remaining, .05)
        time.sleep(delay)

# Makes the current subprocess accessible to the abort() function
# This is a tuple of the Popen object and args.
_currentSubprocess = None

def waitOn(p):
    if get_os() == 'windows':
        # on windows use a poll loop, otherwise signal does not get handled
        retcode = None
        while retcode == None:
            retcode = p.poll()
            time.sleep(0.05)
    else:
        retcode = p.wait()
    return retcode

def run(args, nonZeroIsFatal=True, out=None, err=None, cwd=None, timeout=None, env=None):
    """
    Run a command in a subprocess, wait for it to complete and return the exit status of the process.
    If the exit status is non-zero and `nonZeroIsFatal` is true, then mx is exited with
    the same exit status.
    Each line of the standard output and error streams of the subprocess are redirected to
    out and err if they are callable objects.
    """

    assert isinstance(args, types.ListType), "'args' must be a list: " + str(args)
    for arg in args:
        assert isinstance(arg, types.StringTypes), 'argument is not a string: ' + str(arg)

    if env is None:
        env = os.environ
        
    if _opts.verbose:
        if _opts.very_verbose:
            log('Environment variables:')
            for key in sorted(env.keys()):
                log('    ' + key + '=' + env[key])
        log(' '.join(args))

    if timeout is None and _opts.ptimeout != 0:
        timeout = _opts.ptimeout

    global _currentSubprocess

    try:
        # On Unix, the new subprocess should be in a separate group so that a timeout alarm
        # can use os.killpg() to kill the whole subprocess group
        preexec_fn = None
        creationflags = 0
        if get_os() == 'windows':
            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            preexec_fn = os.setsid

        if not callable(out) and not callable(err) and timeout is None:
            # The preexec_fn=os.setsid
            p = subprocess.Popen(args, cwd=cwd, preexec_fn=preexec_fn, creationflags=creationflags, env=env)
            _currentSubprocess = (p, args)
            retcode = waitOn(p)
        else:
            def redirect(stream, f):
                for line in iter(stream.readline, ''):
                    f(line)
                stream.close()
            stdout=out if not callable(out) else subprocess.PIPE
            stderr=err if not callable(err) else subprocess.PIPE
            p = subprocess.Popen(args, cwd=cwd, stdout=stdout, stderr=stderr, preexec_fn=preexec_fn, creationflags=creationflags, env=env)
            _currentSubprocess = (p, args)
            if callable(out):
                t = Thread(target=redirect, args=(p.stdout, out))
                t.daemon = True # thread dies with the program
                t.start()
            if callable(err):
                t = Thread(target=redirect, args=(p.stderr, err))
                t.daemon = True # thread dies with the program
                t.start()
            if timeout is None or timeout == 0:
                retcode = waitOn(p)
            else:
                if get_os() == 'windows':
                    abort('Use of timeout not (yet) supported on Windows')
                retcode = _waitWithTimeout(p, args, timeout)
    except OSError as e:
        log('Error executing \'' + ' '.join(args) + '\': ' + str(e))
        if _opts.verbose:
            raise e
        abort(e.errno)
    except KeyboardInterrupt:
        abort(1)
    finally:
        _currentSubprocess = None

    if retcode and nonZeroIsFatal:
        if _opts.verbose:
            if _opts.very_verbose:
                raise subprocess.CalledProcessError(retcode, ' '.join(args))
            else:
                log('[exit code: ' + str(retcode)+ ']')
        abort(retcode)

    return retcode

def exe_suffix(name):
    """
    Gets the platform specific suffix for an executable
    """
    if get_os() == 'windows':
        return name + '.exe'
    return name

def add_lib_prefix(name):
    """
    Adds the platform specific library prefix to a name
    """
    os = get_os();
    if os == 'linux' or os == 'solaris' or os == 'darwin':
        return 'lib' + name
    return name

def add_lib_suffix(name):
    """
    Adds the platform specific library suffix to a name
    """
    os = get_os();
    if os == 'windows':
        return name + '.dll'
    if os == 'linux' or os == 'solaris':
        return name + '.so'
    if os == 'darwin':
        return name + '.dylib'
    return name

"""
A JavaCompliance simplifies comparing Java compliance values extracted from a JDK version string.
"""
class JavaCompliance:
    def __init__(self, ver):
        m = re.match('1\.(\d+).*', ver)
        assert m is not None, 'not a recognized version string: ' + ver
        self.value = int(m.group(1))

    def __str__ (self):
        return '1.' + str(self.value)

    def __cmp__ (self, other):
        if isinstance(other, types.StringType):
            other = JavaCompliance(other)

        return cmp(self.value, other.value)
    
"""
A Java version as defined in JSR-56
"""
class JavaVersion:
    def __init__(self, versionString):
        validChar = '[\x21-\x25\x27-\x29\x2c\x2f-\x5e\x60-\x7f]'
        separator = '[.\-_]'
        m = re.match(validChar + '+(' + separator + validChar + '+)*', versionString)
        assert m is not None, 'not a recognized version string: ' + versionString
        self.versionString = versionString;
        self.parts = [int(f) if f.isdigit() else f for f in re.split(separator, versionString)]
        
    def __str__(self):
        return self.versionString
    
    def __cmp__(self, other):
        return cmp(self.parts, other.parts)

"""
A JavaConfig object encapsulates info on how Java commands are run.
"""
class JavaConfig:
    def __init__(self, opts):
        self.jdk = opts.java_home
        self.debug_port = opts.java_dbg_port
        self.jar   = exe_suffix(join(self.jdk, 'bin', 'jar'))
        self.java  = exe_suffix(join(self.jdk, 'bin', 'java'))
        self.javac = exe_suffix(join(self.jdk, 'bin', 'javac'))
        self.javap = exe_suffix(join(self.jdk, 'bin', 'javap'))
        self.javadoc = exe_suffix(join(self.jdk, 'bin', 'javadoc'))
        self._bootclasspath = None

        if not exists(self.java):
            abort('Java launcher derived from JAVA_HOME does not exist: ' + self.java)

        def delAtAndSplit(s):
            return shlex.split(s.lstrip('@'))

        self.java_args = delAtAndSplit(_opts.java_args)
        self.java_args_pfx = sum(map(delAtAndSplit, _opts.java_args_pfx), [])
        self.java_args_sfx = sum(map(delAtAndSplit, _opts.java_args_sfx), [])

        # Prepend the -d64 VM option only if the java command supports it
        try:
            output = subprocess.check_output([self.java, '-d64', '-version'], stderr=subprocess.STDOUT)
            self.java_args = ['-d64'] + self.java_args
        except subprocess.CalledProcessError as e:
            try:
                output = subprocess.check_output([self.java, '-version'], stderr=subprocess.STDOUT)
            except subprocess.CalledProcessError as e:
                print e.output
                abort(e.returncode)

        output = output.split()
        assert output[1] == 'version'
        self.version = JavaVersion(output[2].strip('"'))
        self.javaCompliance = JavaCompliance(self.version.versionString)

        if self.debug_port is not None:
            self.java_args += ['-Xdebug', '-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=' + str(self.debug_port)]

    def format_cmd(self, args):
        return [self.java] + self.java_args_pfx + self.java_args + self.java_args_sfx + args

    def bootclasspath(self):
        if self._bootclasspath is None:
            tmpDir = tempfile.mkdtemp()
            try:
                src = join(tmpDir, 'bootclasspath.java')
                with open(src, 'w') as fp:
                    print >> fp, """
public class bootclasspath {
    public static void main(String[] args) {
        String s = System.getProperty("sun.boot.class.path");
        if (s != null) {
            System.out.println(s);
        }
    }
}"""
                subprocess.check_call([self.javac, '-d', tmpDir, src])
                self._bootclasspath = subprocess.check_output([self.java, '-cp', tmpDir, 'bootclasspath'])
            finally:
                shutil.rmtree(tmpDir)
        return self._bootclasspath

def check_get_env(key):
    """
    Gets an environment variable, aborting with a useful message if it is not set.
    """
    value = get_env(key)
    if value is None:
        abort('Required environment variable ' + key + ' must be set')
    return value

def get_env(key, default=None):
    """
    Gets an environment variable.
    """
    value = os.environ.get(key, default)
    return value

def logv(msg=None):
    if _opts.verbose:
        log(msg)
    
def log(msg=None):
    """
    Write a message to the console.
    All script output goes through this method thus allowing a subclass
    to redirect it.
    """
    if msg is None:
        print
    else:
        print msg

def expand_project_in_class_path_arg(cpArg):
    cp = []
    for part in cpArg.split(os.pathsep):
        if part.startswith('@'):
            cp += classpath(part[1:]).split(os.pathsep)
        else:
            cp.append(part)
    return os.pathsep.join(cp)

def expand_project_in_args(args):
    for i in range(len(args)):
        if args[i] == '-cp' or args[i] == '-classpath':
            if i + 1 < len(args):
                args[i + 1] = expand_project_in_class_path_arg(args[i + 1])
            return


def gmake_cmd():
    for a in ['make', 'gmake', 'gnumake']:
        try:
            output = subprocess.check_output([a, '--version'])
            if 'GNU' in output:
                return a;
        except:
            pass
    abort('Could not find a GNU make executable on the current path.')

def expandvars_in_property(value):
        result = expandvars(value)
        if '$' in result or '%' in result:
            abort('Property contains an undefined environment variable: ' + value)
        return result


def abort(codeOrMessage):
    """
    Aborts the program with a SystemExit exception.
    If 'codeOrMessage' is a plain integer, it specifies the system exit status;
    if it is None, the exit status is zero; if it has another type (such as a string),
    the object's value is printed and the exit status is one.
    """

    #import traceback
    #traceback.print_stack()
    currentSubprocess = _currentSubprocess
    if currentSubprocess is not None:
        p, _ = currentSubprocess
        if get_os() == 'windows':
            p.kill()
        else:
            _kill_process_group(p.pid)
    
    raise SystemExit(codeOrMessage)

def download(path, urls, verbose=False):
    """
    Attempts to downloads content for each URL in a list, stopping after the first successful download.
    If the content cannot be retrieved from any URL, the program is aborted. The downloaded content
    is written to the file indicated by 'path'.
    """
    d = dirname(path)
    if d != '' and not exists(d):
        os.makedirs(d)

    # Try it with the Java tool first since it can show a progress counter
    myDir = dirname(__file__)

    if not path.endswith(os.sep):
        javaSource = join(myDir, 'URLConnectionDownload.java')
        javaClass = join(myDir, 'URLConnectionDownload.class')
        if not exists(javaClass) or getmtime(javaClass) < getmtime(javaSource):
            subprocess.check_call([java().javac, '-d', myDir, javaSource])
        if run([java().java, '-cp', myDir, 'URLConnectionDownload', path] + urls, nonZeroIsFatal=False) == 0:
            return

    def url_open(url):
        userAgent = 'Mozilla/5.0 (compatible)'
        headers = { 'User-Agent' : userAgent }
        req = urllib2.Request(url, headers=headers)
        return urllib2.urlopen(req)

    for url in urls:
        try:
            if (verbose):
                log('Downloading ' + url + ' to ' + path)
            if url.startswith('zip:') or url.startswith('jar:'):
                i = url.find('!/')
                if i == -1:
                    abort('Zip or jar URL does not contain "!/": ' + url)
                url, _, entry = url[len('zip:'):].partition('!/')
                with contextlib.closing(url_open(url)) as f:
                    data = f.read()
                    zipdata = StringIO.StringIO(f.read())

                zf = zipfile.ZipFile(zipdata, 'r')
                data = zf.read(entry)
                with open(path, 'wb') as f:
                    f.write(data)
            else:
                with contextlib.closing(url_open(url)) as f:
                    data = f.read()
                if path.endswith(os.sep):
                    # Scrape directory listing for relative URLs
                    hrefs = re.findall(r' href="([^"]*)"', data)
                    if len(hrefs) != 0:
                        for href in hrefs:
                            if not '/' in href:
                                download(join(path, href), [url + href], verbose)
                    else:
                        log('no locals hrefs scraped from ' + url)
                else:
                    with open(path, 'wb') as f:
                        f.write(data)
            return
        except IOError as e:
            log('Error reading from ' + url + ': ' + str(e))
        except zipfile.BadZipfile as e:
            log('Error in zip file downloaded from ' + url + ': ' + str(e))

    abort('Could not download to ' + path + ' from any of the following URLs:\n\n    ' +
              '\n    '.join(urls) + '\n\nPlease use a web browser to do the download manually')

def update_file(path, content):
    """
    Updates a file with some given content if the content differs from what's in
    the file already. The return value indicates if the file was updated.
    """
    existed = exists(path)
    try:
        old = None
        if existed:
            with open(path, 'rb') as f:
                old = f.read()

        if old == content:
            return False

        with open(path, 'wb') as f:
            f.write(content)

        log(('modified ' if existed else 'created ') + path)
        return True;
    except IOError as e:
        abort('Error while writing to ' + path + ': ' + str(e));

# Builtin commands

def build(args, parser=None):
    """compile the Java and C sources, linking the latter

    Compile all the Java source code using the appropriate compilers
    and linkers for the various source code types."""

    suppliedParser = parser is not None
    if not suppliedParser:
        parser = ArgumentParser(prog='mx build')

    javaCompliance = java().javaCompliance

    defaultEcjPath = join(_mainSuite.dir, 'mx', 'ecj.jar')

    parser = parser if parser is not None else ArgumentParser(prog='mx build')
    parser.add_argument('-f', action='store_true', dest='force', help='force build (disables timestamp checking)')
    parser.add_argument('-c', action='store_true', dest='clean', help='removes existing build output')
    parser.add_argument('--source', dest='compliance', help='Java compliance level for projects without an explicit one', default=str(javaCompliance))
    parser.add_argument('--Wapi', action='store_true', dest='warnAPI', help='show warnings about using internal APIs')
    parser.add_argument('--projects', action='store', help='comma separated projects to build (omit to build all projects)')
    parser.add_argument('--only', action='store', help='comma separated projects to build, without checking their dependencies (omit to build all projects)')
    parser.add_argument('--no-java', action='store_false', dest='java', help='do not build Java projects')
    parser.add_argument('--no-native', action='store_false', dest='native', help='do not build native projects')
    parser.add_argument('--jdt', help='path to ecj.jar, the Eclipse batch compiler (default: ' + defaultEcjPath + ')', default=defaultEcjPath, metavar='<path>')
    parser.add_argument('--jdt-warning-as-error', action='store_true', help='convert all Eclipse batch compiler warnings to errors')

    if suppliedParser:
        parser.add_argument('remainder', nargs=REMAINDER, metavar='...')

    args = parser.parse_args(args)
    
    jdtJar = None
    if args.jdt is not None:
        if args.jdt.endswith('.jar'):
            jdtJar=args.jdt
            if not exists(jdtJar) and os.path.abspath(jdtJar) == os.path.abspath(defaultEcjPath):
                # Silently ignore JDT if default location is used but not ecj.jar exists there
                jdtJar = None

    built = set()

    projects = None
    if args.projects is not None:
        projects = args.projects.split(',')

    if args.only is not None:
        sortedProjects = [project(name) for name in args.only.split(',')]
    else:
        sortedProjects = sorted_deps(projects, includeAnnotationProcessors=True)
    
    for p in sortedProjects:
        if p.native:
            if args.native:
                log('Calling GNU make {0}...'.format(p.dir))

                if args.clean:
                    run([gmake_cmd(), 'clean'], cwd=p.dir)

                run([gmake_cmd()], cwd=p.dir)
                built.add(p.name)
            continue
        else:
            if not args.java:
                continue
            if exists(join(p.dir, 'plugin.xml')): # eclipse plugin project
                continue

        # skip building this Java project if its Java compliance level is "higher" than the configured JDK
        if javaCompliance < p.javaCompliance:
            log('Excluding {0} from build (Java compliance level {1} required)'.format(p.name, p.javaCompliance))
            continue

        outputDir = p.output_dir()
        if exists(outputDir):
            if args.clean:
                log('Cleaning {0}...'.format(outputDir))
                shutil.rmtree(outputDir)
                os.mkdir(outputDir)
        else:
            os.mkdir(outputDir)

        cp = classpath(p.name, includeSelf=True)
        sourceDirs = p.source_dirs()
        mustBuild = args.force
        if not mustBuild:
            for dep in p.all_deps([], False):
                if dep.name in built:
                    mustBuild = True

        jasminAvailable = None
        javafilelist = []
        for sourceDir in sourceDirs:
            for root, _, files in os.walk(sourceDir):
                javafiles = [join(root, name) for name in files if name.endswith('.java') and name != 'package-info.java']
                javafilelist += javafiles

                # Copy all non Java resources or assemble Jasmin files
                nonjavafilelist = [join(root, name) for name in files if not name.endswith('.java')]
                for src in nonjavafilelist:
                    if src.endswith('.jasm'):
                        className = None
                        with open(src) as f:
                            for line in f:
                                if line.startswith('.class '):
                                    className = line.split()[-1]
                                    break

                        if className is not None:
                            jasminOutputDir = p.jasmin_output_dir()
                            classFile = join(jasminOutputDir, className.replace('/', os.sep) + '.class')
                            if exists(dirname(classFile)) and (not exists(classFile) or os.path.getmtime(classFile) < os.path.getmtime(src)):
                                if jasminAvailable is None:
                                    try:
                                        with open(os.devnull) as devnull:
                                            subprocess.call('jasmin', stdout=devnull, stderr=subprocess.STDOUT)
                                        jasminAvailable = True
                                    except OSError:
                                        jasminAvailable = False

                                if jasminAvailable:
                                    log('Assembling Jasmin file ' + src)
                                    run(['jasmin', '-d', jasminOutputDir, src])
                                else:
                                    log('The jasmin executable could not be found - skipping ' + src)
                                    with file(classFile, 'a'):
                                        os.utime(classFile, None)

                        else:
                            log('could not file .class directive in Jasmin source: ' + src)
                    else:
                        dst = join(outputDir, src[len(sourceDir) + 1:])
                        if not exists(dirname(dst)):
                            os.makedirs(dirname(dst))
                        if exists(dirname(dst)) and (not exists(dst) or os.path.getmtime(dst) < os.path.getmtime(src)):
                            shutil.copyfile(src, dst)

                if not mustBuild:
                    for javafile in javafiles:
                        classfile = outputDir + javafile[len(sourceDir):-len('java')] + 'class'
                        if not exists(classfile) or os.path.getmtime(javafile) > os.path.getmtime(classfile):
                            mustBuild = True
                            break

        if not mustBuild:
            logv('[all class files for {0} are up to date - skipping]'.format(p.name))
            continue

        if len(javafilelist) == 0:
            logv('[no Java sources for {0} - skipping]'.format(p.name))
            continue

        built.add(p.name)

        argfileName = join(p.dir, 'javafilelist.txt')
        argfile = open(argfileName, 'wb')
        argfile.write('\n'.join(javafilelist))
        argfile.close()

        processorArgs = []

        ap = p.annotation_processors()
        if len(ap) > 0:
            processorPath = classpath(ap, resolve=True)
            genDir = p.source_gen_dir();
            if exists(genDir):
                shutil.rmtree(genDir)
            os.mkdir(genDir)
            processorArgs += ['-processorpath', join(processorPath), '-s', genDir] 
        else:
            processorArgs += ['-proc:none']

        toBeDeleted = [argfileName]
        try:
            compliance = str(p.javaCompliance) if p.javaCompliance is not None else args.compliance
            if jdtJar is None:
                log('Compiling Java sources for {0} with javac...'.format(p.name))
                
                
                javacCmd = [java().javac, '-g', '-J-Xmx1g', '-source', compliance, '-classpath', cp, '-d', outputDir]
                if java().debug_port is not None:
                    javacCmd += ['-J-Xdebug', '-J-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=' + str(java().debug_port)]
                javacCmd += processorArgs
                javacCmd += ['@' + argfile.name]
                
                if not args.warnAPI:
                    javacCmd.append('-XDignore.symbol.file')
                run(javacCmd)
            else:
                log('Compiling Java sources for {0} with JDT...'.format(p.name))
                                
                jdtArgs = [java().java, '-Xmx1g']
                if java().debug_port is not None:
                    jdtArgs += ['-Xdebug', '-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=' + str(java().debug_port)]

                jdtArgs += [ '-jar', jdtJar,
                         '-' + compliance,
                         '-cp', cp, '-g', '-enableJavadoc',
                         '-d', outputDir]
                jdtArgs += processorArgs
                         
                         
                jdtProperties = join(p.dir, '.settings', 'org.eclipse.jdt.core.prefs')
                rootJdtProperties = join(p.suite.dir, 'mx', 'eclipse-settings', 'org.eclipse.jdt.core.prefs')
                if not exists(jdtProperties) or os.path.getmtime(jdtProperties) < os.path.getmtime(rootJdtProperties):
                    # Try to fix a missing properties file by running eclipseinit
                    eclipseinit([], buildProcessorJars=False)
                if not exists(jdtProperties):
                    log('JDT properties file {0} not found'.format(jdtProperties))
                else:
                    # convert all warnings to errors
                    if args.jdt_warning_as_error:
                        jdtPropertiesTmp = jdtProperties + '.tmp'
                        with open(jdtProperties) as fp:
                            content = fp.read().replace('=warning', '=error')
                        with open(jdtPropertiesTmp, 'w') as fp:
                            fp.write(content)
                        toBeDeleted.append(jdtPropertiesTmp)
                        jdtArgs += ['-properties', jdtPropertiesTmp]
                    else:
                        jdtArgs += ['-properties', jdtProperties]
                jdtArgs.append('@' + argfile.name)
                
                run(jdtArgs)
        finally:
            for n in toBeDeleted:
                os.remove(n)
                
    for dist in _dists.values():
        archive(['@' + dist.name])

    if suppliedParser:
        return args
    return None

def eclipseformat(args):
    """run the Eclipse Code Formatter on the Java sources

    The exit code 1 denotes that at least one file was modified."""

    parser = ArgumentParser(prog='mx eclipseformat')
    parser.add_argument('-e', '--eclipse-exe', help='location of the Eclipse executable')
    parser.add_argument('-C', '--no-backup', action='store_false', dest='backup', help='do not save backup of modified files')
    parser.add_argument('--projects', action='store', help='comma separated projects to process (omit to process all projects)')
    
    args = parser.parse_args(args)
    if args.eclipse_exe is None:
        args.eclipse_exe = os.environ.get('ECLIPSE_EXE')
    if args.eclipse_exe is None:
        abort('Could not find Eclipse executable. Use -e option or ensure ECLIPSE_EXE environment variable is set.')
        
    # Maybe an Eclipse installation dir was specified - look for the executable in it
    if join(args.eclipse_exe, exe_suffix('eclipse')):
        args.eclipse_exe = join(args.eclipse_exe, exe_suffix('eclipse'))
        
    if not os.path.isfile(args.eclipse_exe) or not os.access(args.eclipse_exe, os.X_OK):
        abort('Not an executable file: ' + args.eclipse_exe)

    eclipseinit([], buildProcessorJars=False)

    # build list of projects to be processed
    projects = sorted_deps()
    if args.projects is not None:
        projects = [project(name) for name in args.projects.split(',')]

    class Batch:
        def __init__(self, settingsFile):
            self.path = settingsFile
            self.javafiles = list()
            
        def settings(self):
            with open(self.path) as fp:
                return fp.read()

    class FileInfo:
        def __init__(self, path):
            self.path = path
            with open(path) as fp:
                self.content = fp.read()
            self.times = (os.path.getatime(path), os.path.getmtime(path))

        def update(self):
            with open(self.path) as fp:
                content = fp.read()
                if self.content != content:
                    self.content = content
                    return True
            os.utime(self.path, self.times)
            
    modified = list()
    batches = dict() # all sources with the same formatting settings are formatted together
    for p in projects:
        if p.native:
            continue
        sourceDirs = p.source_dirs()
        
        batch = Batch(join(p.dir, '.settings', 'org.eclipse.jdt.core.prefs'))

        if not exists(batch.path):
            if _opts.verbose:
                log('[no Eclipse Code Formatter preferences at {0} - skipping]'.format(batch.path))
            continue

        for sourceDir in sourceDirs:
            for root, _, files in os.walk(sourceDir):
                for f in [join(root, name) for name in files if name.endswith('.java')]:
                    batch.javafiles.append(FileInfo(f))
        if len(batch.javafiles) == 0:
            logv('[no Java sources in {0} - skipping]'.format(p.name))
            continue

        res = batches.setdefault(batch.settings(), batch)
        if res is not batch:
            res.javafiles = res.javafiles + batch.javafiles

    for batch in batches.itervalues():
        run([args.eclipse_exe, '-nosplash', '-application', 'org.eclipse.jdt.core.JavaCodeFormatter', '-config', batch.path] + [f.path for f in batch.javafiles])
        for fi in batch.javafiles:
            if fi.update():
                modified.append(fi)
                
    log('{0} files were modified'.format(len(modified)))
    if len(modified) != 0:
        if args.backup:
            backup = os.path.abspath('eclipseformat.backup.zip')
            arcbase = _mainSuite.dir
            zf = zipfile.ZipFile(backup, 'w', zipfile.ZIP_DEFLATED)
            for fi in modified:
                arcname = os.path.relpath(fi.path, arcbase).replace(os.sep, '/')
                zf.writestr(arcname, fi.content)
            zf.close()
            log('Wrote backup of {0} modified files to {1}'.format(len(modified), backup))
        return 1
    return 0

def processorjars():
    
    projs = set()
    for p in sorted_deps():
        if _isAnnotationProcessorDependency(p):
            projs.add(p)
            
    if len(projs) < 0:
        return
    
    pnames = [p.name for p in projs]
    build(['--projects', ",".join(pnames)])
    archive(pnames)

def archive(args):
    """create jar files for projects and distributions"""
    parser = ArgumentParser(prog='mx archive');
    parser.add_argument('names', nargs=REMAINDER, metavar='[<project>|@<distribution>]...')
    args = parser.parse_args(args)
    
    for name in args.names:
        if name.startswith('@'):
            dname = name[1:]
            d = distribution(dname)
            fd, tmp = tempfile.mkstemp(suffix='', prefix=basename(d.path) + '.', dir=dirname(d.path))
            services = tempfile.mkdtemp(suffix='', prefix=basename(d.path) + '.', dir=dirname(d.path))

            def overwriteCheck(zf, arcname, source):
                if arcname in zf.namelist():
                    log('warning: ' + d.path + ': overwriting ' + arcname + ' [source: ' + source + ']')
                
            try:
                zf = zipfile.ZipFile(tmp, 'w')
                for dep in sorted_deps(d.deps, includeLibs=True):
                    if dep.isLibrary():
                        l = dep
                        # merge library jar into distribution jar
                        logv('[' + d.path + ': adding library ' + l.name + ']')
                        lpath = l.get_path(resolve=True)
                        with zipfile.ZipFile(lpath, 'r') as lp:
                            for arcname in lp.namelist():
                                if arcname.startswith('META-INF/services/'):
                                    f = arcname[len('META-INF/services/'):].replace('/', os.sep)
                                    with open(join(services, f), 'a') as outfile:
                                        for line in lp.read(arcname).splitlines():
                                            outfile.write(line)
                                else:
                                    overwriteCheck(zf, arcname, lpath + '!' + arcname)
                                    zf.writestr(arcname, lp.read(arcname))
                    else:
                        p = dep
                        # skip a  Java project if its Java compliance level is "higher" than the configured JDK
                        if java().javaCompliance < p.javaCompliance:
                            log('Excluding {0} from {2} (Java compliance level {1} required)'.format(p.name, p.javaCompliance, d.path))
                            continue
    
                        logv('[' + d.path + ': adding project ' + p.name + ']')
                        outputDir = p.output_dir()
                        for root, _, files in os.walk(outputDir):
                            relpath = root[len(outputDir) + 1:]
                            if relpath == join('META-INF', 'services'):
                                for f in files:
                                    with open(join(services, f), 'a') as outfile:
                                        with open(join(root, f), 'r') as infile:
                                            for line in infile:
                                                outfile.write(line)
                            elif relpath == join('META-INF', 'providers'):
                                for f in files:
                                    with open(join(root, f), 'r') as infile:
                                        for line in infile:
                                            with open(join(services, line.strip()), 'a') as outfile:
                                                outfile.write(f + '\n')
                            else:
                                for f in files:
                                    arcname = join(relpath, f).replace(os.sep, '/')
                                    overwriteCheck(zf, arcname, join(root, f))
                                    zf.write(join(root, f), arcname)
                for f in os.listdir(services):
                    arcname = join('META-INF', 'services', f).replace(os.sep, '/')
                    zf.write(join(services, f), arcname)
                zf.close()
                os.close(fd)
                shutil.rmtree(services)
                # Atomic on Unix
                shutil.move(tmp, d.path)
                #print time.time(), 'move:', tmp, '->', d.path
                d.notify_updated()
            finally:
                if exists(tmp):
                    os.remove(tmp)
                if exists(services):
                    shutil.rmtree(services)

        else:
            p = project(name)
            outputDir = p.output_dir()
            fd, tmp = tempfile.mkstemp(suffix='', prefix=p.name, dir=p.dir)
            try:
                zf = zipfile.ZipFile(tmp, 'w')
                for root, _, files in os.walk(outputDir):
                    for f in files:
                        relpath = root[len(outputDir) + 1:]
                        arcname = join(relpath, f).replace(os.sep, '/')
                        zf.write(join(root, f), arcname)
                zf.close()
                os.close(fd)
                # Atomic on Unix
                shutil.move(tmp, join(p.dir, p.name + '.jar'))
            finally:
                if exists(tmp):
                    os.remove(tmp)

def canonicalizeprojects(args):
    """process all project files to canonicalize the dependencies

    The exit code of this command reflects how many files were updated."""

    changedFiles = 0
    for s in suites():
        projectsFile = join(s.dir, 'mx', 'projects')
        if not exists(projectsFile):
            continue
        with open(projectsFile) as f:
            out = StringIO.StringIO()
            pattern = re.compile('project@([^@]+)@dependencies=.*')
            lineNo = 1
            for line in f:
                line = line.strip()
                m = pattern.match(line)
                if m is None:
                    out.write(line + '\n')
                else:
                    p = project(m.group(1))
                    
                    for pkg in p.defined_java_packages():
                        if not pkg.startswith(p.name):
                            abort('package in {0} does not have prefix matching project name: {1}'.format(p, pkg))
                    
                    ignoredDeps = set([name for name in p.deps if project(name, False) is not None])
                    for pkg in p.imported_java_packages():
                        for name in p.deps:
                            dep = project(name, False)
                            if dep is None:
                                ignoredDeps.discard(name)
                            else:
                                if pkg in dep.defined_java_packages():
                                    ignoredDeps.discard(name)
                                if pkg in dep.extended_java_packages():
                                    ignoredDeps.discard(name)
                    if len(ignoredDeps) != 0:
                        candidates = set();
                        # Compute dependencies based on projects required by p
                        for d in sorted_deps():
                            if not d.defined_java_packages().isdisjoint(p.imported_java_packages()):
                                candidates.add(d)
                        # Remove non-canonical candidates
                        for c in list(candidates):
                            candidates.difference_update(c.all_deps([], False, False))
                        candidates = [d.name for d in candidates]
                        
                        abort('{0}:{1}: {2} does not use any packages defined in these projects: {3}\nComputed project dependencies: {4}'.format(
                            projectsFile, lineNo, p, ', '.join(ignoredDeps), ','.join(candidates)))
                    
                    out.write('project@' + m.group(1) + '@dependencies=' + ','.join(p.canonical_deps()) + '\n')
                lineNo = lineNo + 1
            content = out.getvalue()
        if update_file(projectsFile, content):
            changedFiles += 1
    return changedFiles;

def checkstyle(args):
    """run Checkstyle on the Java sources

   Run Checkstyle over the Java sources. Any errors or warnings
   produced by Checkstyle result in a non-zero exit code."""

    parser = ArgumentParser(prog='mx checkstyle')

    parser.add_argument('-f', action='store_true', dest='force', help='force checking (disables timestamp checking)')
    args = parser.parse_args(args)
    
    totalErrors = 0
    for p in sorted_deps():
        if p.native:
            continue
        sourceDirs = p.source_dirs()
        dotCheckstyle = join(p.dir, '.checkstyle')

        if not exists(dotCheckstyle):
            continue
        
        # skip checking this Java project if its Java compliance level is "higher" than the configured JDK
        if java().javaCompliance < p.javaCompliance:
            log('Excluding {0} from checking (Java compliance level {1} required)'.format(p.name, p.javaCompliance))
            continue

        for sourceDir in sourceDirs:
            javafilelist = []
            for root, _, files in os.walk(sourceDir):
                javafilelist += [join(root, name) for name in files if name.endswith('.java') and name != 'package-info.java']
            if len(javafilelist) == 0:
                logv('[no Java sources in {0} - skipping]'.format(sourceDir))
                continue

            timestampFile = join(p.suite.dir, 'mx', 'checkstyle-timestamps', sourceDir[len(p.suite.dir) + 1:].replace(os.sep, '_') + '.timestamp')
            if not exists(dirname(timestampFile)):
                os.makedirs(dirname(timestampFile))
            mustCheck = False
            if not args.force and exists(timestampFile):
                timestamp = os.path.getmtime(timestampFile)
                for f in javafilelist:
                    if os.path.getmtime(f) > timestamp:
                        mustCheck = True
                        break
            else:
                mustCheck = True

            if not mustCheck:
                if _opts.verbose:
                    log('[all Java sources in {0} already checked - skipping]'.format(sourceDir))
                continue

            dotCheckstyleXML = xml.dom.minidom.parse(dotCheckstyle)
            localCheckConfig = dotCheckstyleXML.getElementsByTagName('local-check-config')[0]
            configLocation = localCheckConfig.getAttribute('location')
            configType = localCheckConfig.getAttribute('type')
            if configType == 'project':
                # Eclipse plugin "Project Relative Configuration" format:
                #
                #  '/<project_name>/<suffix>'
                #
                if configLocation.startswith('/'):
                    name, _, suffix = configLocation.lstrip('/').partition('/')
                    config = join(project(name).dir, suffix)
                else:
                    config = join(p.dir, configLocation)
            else:
                logv('[unknown Checkstyle configuration type "' + configType + '" in {0} - skipping]'.format(sourceDir))
                continue

            exclude = join(p.dir, '.checkstyle.exclude')

            if exists(exclude):
                with open(exclude) as f:
                    # Convert patterns to OS separators
                    patterns = [name.rstrip().replace('/', os.sep) for name in f.readlines()]
                def match(name):
                    for p in patterns:
                        if p in name:
                            if _opts.verbose:
                                log('excluding: ' + name)
                            return True
                    return False

                javafilelist = [name for name in javafilelist if not match(name)]

            auditfileName = join(p.dir, 'checkstyleOutput.txt')
            log('Running Checkstyle on {0} using {1}...'.format(sourceDir, config))

            try:

                # Checkstyle is unable to read the filenames to process from a file, and the
                # CreateProcess function on Windows limits the length of a command line to
                # 32,768 characters (http://msdn.microsoft.com/en-us/library/ms682425%28VS.85%29.aspx)
                # so calling Checkstyle must be done in batches.
                while len(javafilelist) != 0:
                    i = 0
                    size = 0
                    while i < len(javafilelist):
                        s = len(javafilelist[i]) + 1
                        if (size + s < 30000):
                            size += s
                            i += 1
                        else:
                            break

                    batch = javafilelist[:i]
                    javafilelist = javafilelist[i:]
                    try:
                        run_java(['-Xmx1g', '-jar', library('CHECKSTYLE').get_path(True), '-f', 'xml', '-c', config, '-o', auditfileName] + batch, nonZeroIsFatal=False)
                    finally:
                        if exists(auditfileName):
                            errors = []
                            source = None
                            def start_element(name, attrs):
                                if name == 'file':
                                    global source
                                    source = attrs['name']
                                elif name == 'error':
                                    errors.append('{}:{}: {}'.format(source, attrs['line'], attrs['message']))

                            p = xml.parsers.expat.ParserCreate()
                            p.StartElementHandler = start_element
                            with open(auditfileName) as fp:
                                p.ParseFile(fp)
                            if len(errors) != 0:
                                map(log, errors)
                                totalErrors = totalErrors + len(errors)
                            else:
                                if exists(timestampFile):
                                    os.utime(timestampFile, None)
                                else:
                                    file(timestampFile, 'a')
            finally:
                if exists(auditfileName):
                    os.unlink(auditfileName)
    return totalErrors

def clean(args, parser=None):
    """remove all class files, images, and executables

    Removes all files created by a build, including Java class files, executables, and
    generated images.
    """

    suppliedParser = parser is not None

    parser = parser if suppliedParser else ArgumentParser(prog='mx build');
    parser.add_argument('--no-native', action='store_false', dest='native', help='do not clean native projects')
    parser.add_argument('--no-java', action='store_false', dest='java', help='do not clean Java projects')

    args = parser.parse_args(args)

    for p in projects():
        if p.native:
            if args.native:
                run([gmake_cmd(), '-C', p.dir, 'clean'])
        else:
            if args.java:
                genDir = p.source_gen_dir();
                if genDir != '' and exists(genDir):
                    log('Clearing {0}...'.format(genDir))
                    for f in os.listdir(genDir):
                        shutil.rmtree(join(genDir, f))
                    
                    
                outputDir = p.output_dir()
                if outputDir != '' and exists(outputDir):
                    log('Removing {0}...'.format(outputDir))
                    shutil.rmtree(outputDir)

    if suppliedParser:
        return args

def about(args):
    """show the 'man page' for mx"""
    print __doc__

def help_(args):
    """show help for a given command

With no arguments, print a list of commands and short help for each command.

Given a command name, print help for that command."""
    if len(args) == 0:
        _argParser.print_help()
        return

    name = args[0]
    if not commands.has_key(name):
        hits = [c for c in commands.iterkeys() if c.startswith(name)]
        if len(hits) == 1:
            name = hits[0]
        elif len(hits) == 0:
            abort('mx: unknown command \'{0}\'\n{1}use "mx help" for more options'.format(name, _format_commands()))
        else:
            abort('mx: command \'{0}\' is ambiguous\n    {1}'.format(name, ' '.join(hits)))

    value = commands[name]
    (func, usage) = value[:2]
    doc = func.__doc__
    if len(value) > 2:
        docArgs = value[2:]
        fmtArgs = []
        for d in docArgs:
            if isinstance(d, Callable):
                fmtArgs += [d()]
            else:
                fmtArgs += [str(d)]
        doc = doc.format(*fmtArgs)
    print 'mx {0} {1}\n\n{2}\n'.format(name, usage, doc)

def projectgraph(args, suite=None):
    """create dot graph for project structure ("mx projectgraph | dot -Tpdf -oprojects.pdf")"""

    print 'digraph projects {'
    print 'rankdir=BT;'
    print 'node [shape=rect];'
    for p in projects():
        for dep in p.canonical_deps():
            print '"' + p.name + '"->"' + dep + '"'
    print '}'

def _source_locator_memento(deps):
    slm = XMLDoc()
    slm.open('sourceLookupDirector')
    slm.open('sourceContainers', {'duplicates' : 'false'})

    # Every Java program depends on the JRE
    memento = XMLDoc().element('classpathContainer', {'path' : 'org.eclipse.jdt.launching.JRE_CONTAINER'}).xml(standalone='no')
    slm.element('classpathContainer', {'memento' : memento, 'typeId':'org.eclipse.jdt.launching.sourceContainer.classpathContainer'})

    for dep in deps:
        if dep.isLibrary():
            if hasattr(dep, 'eclipse.container'):
                memento = XMLDoc().element('classpathContainer', {'path' : getattr(dep, 'eclipse.container')}).xml(standalone='no')
                slm.element('classpathContainer', {'memento' : memento, 'typeId':'org.eclipse.jdt.launching.sourceContainer.classpathContainer'})
        else:
            memento = XMLDoc().element('javaProject', {'name' : dep.name}).xml(standalone='no')
            slm.element('container', {'memento' : memento, 'typeId':'org.eclipse.jdt.launching.sourceContainer.javaProject'})

    slm.close('sourceContainers')
    slm.close('sourceLookupDirector')
    return slm

def make_eclipse_attach(hostname, port, name=None, deps=[]):
    """
    Creates an Eclipse launch configuration file for attaching to a Java process.
    """
    slm = _source_locator_memento(deps)
    launch = XMLDoc()
    launch.open('launchConfiguration', {'type' : 'org.eclipse.jdt.launching.remoteJavaApplication'})
    launch.element('stringAttribute', {'key' : 'org.eclipse.debug.core.source_locator_id', 'value' : 'org.eclipse.jdt.launching.sourceLocator.JavaSourceLookupDirector'})
    launch.element('stringAttribute', {'key' : 'org.eclipse.debug.core.source_locator_memento', 'value' : '%s'})
    launch.element('booleanAttribute', {'key' : 'org.eclipse.jdt.launching.ALLOW_TERMINATE', 'value' : 'true'})
    launch.open('mapAttribute', {'key' : 'org.eclipse.jdt.launching.CONNECT_MAP'})
    launch.element('mapEntry', {'key' : 'hostname', 'value' : hostname})
    launch.element('mapEntry', {'key' : 'port', 'value' : port})
    launch.close('mapAttribute')
    launch.element('stringAttribute', {'key' : 'org.eclipse.jdt.launching.PROJECT_ATTR', 'value' : ''})
    launch.element('stringAttribute', {'key' : 'org.eclipse.jdt.launching.VM_CONNECTOR_ID', 'value' : 'org.eclipse.jdt.launching.socketAttachConnector'})
    launch.close('launchConfiguration')
    launch = launch.xml(newl='\n', standalone='no') % slm.xml(escape=True, standalone='no')

    if name is None:
        name = 'attach-' + hostname + '-' + port
    eclipseLaunches = join('mx', 'eclipse-launches')
    if not exists(eclipseLaunches):
        os.makedirs(eclipseLaunches)
    return update_file(join(eclipseLaunches, name + '.launch'), launch)

def make_eclipse_launch(javaArgs, jre, name=None, deps=[]):
    """
    Creates an Eclipse launch configuration file for running/debugging a Java command.
    """
    mainClass = None
    vmArgs = []
    appArgs = []
    cp = None
    argsCopy = list(reversed(javaArgs))
    while len(argsCopy) != 0:
        a = argsCopy.pop()
        if a == '-jar':
            mainClass = '-jar'
            appArgs = list(reversed(argsCopy))
            break
        if a == '-cp' or a == '-classpath':
            assert len(argsCopy) != 0
            cp = argsCopy.pop()
            vmArgs.append(a)
            vmArgs.append(cp)
        elif a.startswith('-'):
            vmArgs.append(a)
        else:
            mainClass = a
            appArgs = list(reversed(argsCopy))
            break

    if mainClass is None:
        log('Cannot create Eclipse launch configuration without main class or jar file: java ' + ' '.join(javaArgs))
        return False

    if name is None:
        if mainClass == '-jar':
            name = basename(appArgs[0])
            if len(appArgs) > 1 and not appArgs[1].startswith('-'):
                name = name + '_' + appArgs[1]
        else:
            name = mainClass
        name = time.strftime('%Y-%m-%d-%H%M%S_' + name)

    if cp is not None:
        for e in cp.split(os.pathsep):
            for s in suites():
                deps += [p for p in s.projects if e == p.output_dir()]
                deps += [l for l in s.libs if e == l.get_path(False)]

    slm = _source_locator_memento(deps)

    launch = XMLDoc()
    launch.open('launchConfiguration', {'type' : 'org.eclipse.jdt.launching.localJavaApplication'})
    launch.element('stringAttribute', {'key' : 'org.eclipse.debug.core.source_locator_id', 'value' : 'org.eclipse.jdt.launching.sourceLocator.JavaSourceLookupDirector'})
    launch.element('stringAttribute', {'key' : 'org.eclipse.debug.core.source_locator_memento', 'value' : '%s'})
    launch.element('stringAttribute', {'key' : 'org.eclipse.jdt.launching.JRE_CONTAINER', 'value' : 'org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/' + jre})
    launch.element('stringAttribute', {'key' : 'org.eclipse.jdt.launching.MAIN_TYPE', 'value' : mainClass})
    launch.element('stringAttribute', {'key' : 'org.eclipse.jdt.launching.PROGRAM_ARGUMENTS', 'value' : ' '.join(appArgs)})
    launch.element('stringAttribute', {'key' : 'org.eclipse.jdt.launching.PROJECT_ATTR', 'value' : ''})
    launch.element('stringAttribute', {'key' : 'org.eclipse.jdt.launching.VM_ARGUMENTS', 'value' : ' '.join(vmArgs)})
    launch.close('launchConfiguration')
    launch = launch.xml(newl='\n', standalone='no') % slm.xml(escape=True, standalone='no')

    eclipseLaunches = join('mx', 'eclipse-launches')
    if not exists(eclipseLaunches):
        os.makedirs(eclipseLaunches)
    return update_file(join(eclipseLaunches, name + '.launch'), launch)

def eclipseinit(args, suite=None, buildProcessorJars=True):
    """(re)generate Eclipse project configurations and working sets"""

    if suite is None:
        suite = _mainSuite
        
    if buildProcessorJars:
        processorjars()

    projToDist = dict()
    for dist in _dists.values():
        distDeps = sorted_deps(dist.deps)
        for p in distDeps:
            projToDist[p.name] = (dist, [dep.name for dep in distDeps])

    for p in projects():
        if p.native:
            continue

        if not exists(p.dir):
            os.makedirs(p.dir)

        out = XMLDoc()
        out.open('classpath')

        for src in p.srcDirs:
            srcDir = join(p.dir, src)
            if not exists(srcDir):
                os.mkdir(srcDir)
            out.element('classpathentry', {'kind' : 'src', 'path' : src})

        if len(p.annotation_processors()) > 0:
            genDir = p.source_gen_dir();
            if not exists(genDir):
                os.mkdir(genDir)
            out.element('classpathentry', {'kind' : 'src', 'path' : 'src_gen'})

        # Every Java program depends on the JRE
        out.element('classpathentry', {'kind' : 'con', 'path' : 'org.eclipse.jdt.launching.JRE_CONTAINER'})

        if exists(join(p.dir, 'plugin.xml')): # eclipse plugin project
            out.element('classpathentry', {'kind' : 'con', 'path' : 'org.eclipse.pde.core.requiredPlugins'})

        for dep in p.all_deps([], True):
            if dep == p:
                continue;

            if dep.isLibrary():
                if hasattr(dep, 'eclipse.container'):
                    out.element('classpathentry', {'exported' : 'true', 'kind' : 'con', 'path' : getattr(dep, 'eclipse.container')})
                elif hasattr(dep, 'eclipse.project'):
                    out.element('classpathentry', {'combineaccessrules' : 'false', 'exported' : 'true', 'kind' : 'src', 'path' : '/' + getattr(dep, 'eclipse.project')})
                else:
                    path = dep.path
                    if dep.mustExist:
                        dep.get_path(resolve=True)
                        if not isabs(path):
                            # Relative paths for "lib" class path entries have various semantics depending on the Eclipse
                            # version being used (e.g. see https://bugs.eclipse.org/bugs/show_bug.cgi?id=274737) so it's
                            # safest to simply use absolute paths.
                            path = join(suite.dir, path)

                        attributes = {'exported' : 'true', 'kind' : 'lib', 'path' : path}

                        sourcePath = dep.get_source_path(resolve=True)
                        if sourcePath is not None:
                            attributes['sourcepath'] = sourcePath
                        out.element('classpathentry', attributes)
            else:
                out.element('classpathentry', {'combineaccessrules' : 'false', 'exported' : 'true', 'kind' : 'src', 'path' : '/' + dep.name})

        out.element('classpathentry', {'kind' : 'output', 'path' : getattr(p, 'eclipse.output', 'bin')})
        out.close('classpath')
        update_file(join(p.dir, '.classpath'), out.xml(indent='\t', newl='\n'))

        csConfig = join(project(p.checkstyleProj).dir, '.checkstyle_checks.xml')
        if exists(csConfig):
            out = XMLDoc()

            dotCheckstyle = join(p.dir, ".checkstyle")
            checkstyleConfigPath = '/' + p.checkstyleProj + '/.checkstyle_checks.xml'
            out.open('fileset-config', {'file-format-version' : '1.2.0', 'simple-config' : 'true'})
            out.open('local-check-config', {'name' : 'Checks', 'location' : checkstyleConfigPath, 'type' : 'project', 'description' : ''})
            out.element('additional-data', {'name' : 'protect-config-file', 'value' : 'false'})
            out.close('local-check-config')
            out.open('fileset', {'name' : 'all', 'enabled' : 'true', 'check-config-name' : 'Checks', 'local' : 'true'})
            out.element('file-match-pattern', {'match-pattern' : '.', 'include-pattern' : 'true'})
            out.close('fileset')
            out.open('filter', {'name' : 'all', 'enabled' : 'true', 'check-config-name' : 'Checks', 'local' : 'true'})
            out.element('filter-data', {'value' : 'java'})
            out.close('filter')

            exclude = join(p.dir, '.checkstyle.exclude')
            if exists(exclude):
                out.open('filter', {'name' : 'FilesFromPackage', 'enabled' : 'true'})
                with open(exclude) as f:
                    for line in f:
                        if not line.startswith('#'):
                            line = line.strip()
                            exclDir = join(p.dir, line)
                            assert isdir(exclDir), 'excluded source directory listed in ' + exclude + ' does not exist or is not a directory: ' + exclDir
                        out.element('filter-data', {'value' : line})
                out.close('filter')

            out.close('fileset-config')
            update_file(dotCheckstyle, out.xml(indent='  ', newl='\n'))

        out = XMLDoc()
        out.open('projectDescription')
        out.element('name', data=p.name)
        out.element('comment', data='')
        out.element('projects', data='')
        out.open('buildSpec')
        out.open('buildCommand')
        out.element('name', data='org.eclipse.jdt.core.javabuilder')
        out.element('arguments', data='')
        out.close('buildCommand')
        if exists(csConfig):
            out.open('buildCommand')
            out.element('name', data='net.sf.eclipsecs.core.CheckstyleBuilder')
            out.element('arguments', data='')
            out.close('buildCommand')
        if exists(join(p.dir, 'plugin.xml')): # eclipse plugin project
            for buildCommand in ['org.eclipse.pde.ManifestBuilder', 'org.eclipse.pde.SchemaBuilder']:
                out.open('buildCommand')
                out.element('name', data=buildCommand)
                out.element('arguments', data='')
                out.close('buildCommand')

        if _isAnnotationProcessorDependency(p):
            _genEclipseBuilder(out, p, 'Jar.launch', 'archive ' + p.name, refresh = False, async = False, xmlIndent='', xmlStandalone='no')
            _genEclipseBuilder(out, p, 'Refresh.launch', '', refresh = True, async = True)
                       
        if projToDist.has_key(p.name):
            dist, distDeps = projToDist[p.name]
            _genEclipseBuilder(out, p, 'Create' + dist.name + 'Dist.launch', 'archive @' + dist.name, refresh=False, async=True)
        
        out.close('buildSpec')
        out.open('natures')
        out.element('nature', data='org.eclipse.jdt.core.javanature')
        if exists(csConfig):
            out.element('nature', data='net.sf.eclipsecs.core.CheckstyleNature')
        if exists(join(p.dir, 'plugin.xml')): # eclipse plugin project
            out.element('nature', data='org.eclipse.pde.PluginNature')
        out.close('natures')
        out.close('projectDescription')
        update_file(join(p.dir, '.project'), out.xml(indent='\t', newl='\n'))

        settingsDir = join(p.dir, ".settings")
        if not exists(settingsDir):
            os.mkdir(settingsDir)

        eclipseSettingsDir = join(suite.dir, 'mx', 'eclipse-settings')
        if exists(eclipseSettingsDir):
            for name in os.listdir(eclipseSettingsDir):
                if name == "org.eclipse.jdt.apt.core.prefs" and not len(p.annotation_processors()) > 0:
                    continue
                path = join(eclipseSettingsDir, name)
                if isfile(path):
                    with open(join(eclipseSettingsDir, name)) as f:
                        content = f.read()
                    content = content.replace('${javaCompliance}', str(p.javaCompliance))
                    if len(p.annotation_processors()) > 0:
                        content = content.replace('org.eclipse.jdt.core.compiler.processAnnotations=disabled', 'org.eclipse.jdt.core.compiler.processAnnotations=enabled')
                    update_file(join(settingsDir, name), content)
        
        if len(p.annotation_processors()) > 0:
            out = XMLDoc()
            out.open('factorypath')
            out.element('factorypathentry', {'kind' : 'PLUGIN', 'id' : 'org.eclipse.jst.ws.annotations.core', 'enabled' : 'true', 'runInBatchMode' : 'false'})
            for ap in p.annotation_processors():
                for dep in dependency(ap).all_deps([], True):
                    if dep.isLibrary():
                        if not hasattr(dep, 'eclipse.container') and not hasattr(dep, 'eclipse.project'):
                            if dep.mustExist:
                                path = dep.get_path(resolve=True)
                                if not isabs(path):
                                    # Relative paths for "lib" class path entries have various semantics depending on the Eclipse
                                    # version being used (e.g. see https://bugs.eclipse.org/bugs/show_bug.cgi?id=274737) so it's
                                    # safest to simply use absolute paths.
                                    path = join(suite.dir, path)
                                out.element('factorypathentry', {'kind' : 'EXTJAR', 'id' : path, 'enabled' : 'true', 'runInBatchMode' : 'false'})
                    else:
                        out.element('factorypathentry', {'kind' : 'WKSPJAR', 'id' : '/' + dep.name + '/' + dep.name + '.jar', 'enabled' : 'true', 'runInBatchMode' : 'false'})
            out.close('factorypath')
            update_file(join(p.dir, '.factorypath'), out.xml(indent='\t', newl='\n'))

    make_eclipse_attach('localhost', '8000', deps=projects())
    generate_eclipse_workingsets(suite)


def _isAnnotationProcessorDependency(p):
    """
    Determines if a given project is part of an annotation processor.
    """
    return p in sorted_deps(annotation_processors())

def _genEclipseBuilder(dotProjectDoc, p, name, mxCommand, refresh=True, async=False, logToConsole=False, xmlIndent='\t', xmlStandalone=None):
    launchOut = XMLDoc();
    consoleOn = 'true' if logToConsole else 'false'
    launchOut.open('launchConfiguration', {'type' : 'org.eclipse.ui.externaltools.ProgramBuilderLaunchConfigurationType'})
    launchOut.element('booleanAttribute', {'key' : 'org.eclipse.debug.core.capture_output',                'value': consoleOn})
    launchOut.open('mapAttribute', {'key' : 'org.eclipse.debug.core.environmentVariables'})
    launchOut.element('mapEntry', {'key' : 'JAVA_HOME',	'value' : java().jdk})
    launchOut.close('mapAttribute')
    
    if refresh:
        launchOut.element('stringAttribute',  {'key' : 'org.eclipse.debug.core.ATTR_REFRESH_SCOPE',            'value': '${project}'})
    launchOut.element('booleanAttribute', {'key' : 'org.eclipse.debug.ui.ATTR_CONSOLE_OUTPUT_ON',          'value': consoleOn})
    launchOut.element('booleanAttribute', {'key' : 'org.eclipse.debug.ui.ATTR_LAUNCH_IN_BACKGROUND',       'value': 'true' if async else 'false'})
    
    baseDir = dirname(dirname(os.path.abspath(__file__)))
    
    cmd = 'mx.sh'
    if get_os() == 'windows':
        cmd = 'mx.cmd'
    launchOut.element('stringAttribute',  {'key' : 'org.eclipse.ui.externaltools.ATTR_LOCATION',           'value': join(baseDir, cmd) })
    launchOut.element('stringAttribute',  {'key' : 'org.eclipse.ui.externaltools.ATTR_RUN_BUILD_KINDS',    'value': 'auto,full,incremental'})
    launchOut.element('stringAttribute',  {'key' : 'org.eclipse.ui.externaltools.ATTR_TOOL_ARGUMENTS',     'value': mxCommand})
    launchOut.element('booleanAttribute', {'key' : 'org.eclipse.ui.externaltools.ATTR_TRIGGERS_CONFIGURED','value': 'true'})
    launchOut.element('stringAttribute',  {'key' : 'org.eclipse.ui.externaltools.ATTR_WORKING_DIRECTORY',  'value': p.suite.dir})
    
    
    launchOut.close('launchConfiguration')
    
    externalToolDir = join(p.dir, '.externalToolBuilders')
    
    if not exists(externalToolDir):
        os.makedirs(externalToolDir)
    update_file(join(externalToolDir, name), launchOut.xml(indent=xmlIndent, standalone=xmlStandalone, newl='\n'))
    
    dotProjectDoc.open('buildCommand')
    dotProjectDoc.element('name', data='org.eclipse.ui.externaltools.ExternalToolBuilder')
    dotProjectDoc.element('triggers', data='auto,full,incremental,')
    dotProjectDoc.open('arguments')
    dotProjectDoc.open('dictionary')
    dotProjectDoc.element('key', data = 'LaunchConfigHandle')
    dotProjectDoc.element('value', data = '<project>/.externalToolBuilders/' + name)
    dotProjectDoc.close('dictionary')
    dotProjectDoc.open('dictionary')
    dotProjectDoc.element('key', data = 'incclean')
    dotProjectDoc.element('value', data = 'true')
    dotProjectDoc.close('dictionary')
    dotProjectDoc.close('arguments')
    dotProjectDoc.close('buildCommand')

def generate_eclipse_workingsets(suite):
    """
    Populate the workspace's working set configuration with working sets generated from project data.
    If the workspace already contains working set definitions, the existing ones will be retained and extended.
    In case mx/env does not contain a WORKSPACE definition pointing to the workspace root directory, the Graal project root directory will be assumed.
    If no workspace root directory can be identified, the Graal project root directory is used and the user has to place the workingsets.xml file by hand. 
    """
    
    # identify the location where to look for workingsets.xml
    wsfilename = 'workingsets.xml'
    wsroot = suite.dir
    if os.environ.has_key('WORKSPACE'):
        wsroot = os.environ['WORKSPACE']
    wsdir = join(wsroot, '.metadata/.plugins/org.eclipse.ui.workbench')
    if not exists(wsdir):
        wsdir = wsroot
    wspath = join(wsdir, wsfilename)
    
    # gather working set info from project data
    workingSets = dict()
    for p in projects():
        if p.workingSets is None:
            continue
        for w in p.workingSets.split(","):
            if not workingSets.has_key(w):
                workingSets[w] = [p.name]
            else:
                workingSets[w].append(p.name)
    
    if exists(wspath):
        wsdoc = _copy_workingset_xml(wspath, workingSets)
    else:
        wsdoc = _make_workingset_xml(workingSets)
    
    update_file(wspath, wsdoc.xml(newl='\n'))

def _make_workingset_xml(workingSets):
    wsdoc = XMLDoc()
    wsdoc.open('workingSetManager')
    
    for w in sorted(workingSets.keys()):
        _workingset_open(wsdoc, w)
        for p in workingSets[w]:
            _workingset_element(wsdoc, p)
        wsdoc.close('workingSet')
    
    wsdoc.close('workingSetManager')
    return wsdoc

def _copy_workingset_xml(wspath, workingSets):
    target = XMLDoc()
    target.open('workingSetManager')

    parser = xml.parsers.expat.ParserCreate()
    
    class ParserState(object):
        def __init__(self):
            self.current_ws_name = 'none yet'
            self.current_ws = None
            self.seen_ws = list()
            self.seen_projects = list()
    
    ps = ParserState()
    
    # parsing logic
    def _ws_start(name, attributes):
        if name == 'workingSet':
            ps.current_ws_name = attributes['name']
            if workingSets.has_key(ps.current_ws_name):
                ps.current_ws = workingSets[ps.current_ws_name]
                ps.seen_ws.append(ps.current_ws_name)
                ps.seen_projects = list()
            else:
                ps.current_ws = None
            target.open(name, attributes)
            parser.StartElementHandler = _ws_item
    
    def _ws_end(name):
        if name == 'workingSet':
            if not ps.current_ws is None:
                for p in ps.current_ws:
                    if not p in ps.seen_projects:
                        _workingset_element(target, p)
            target.close('workingSet')
            parser.StartElementHandler = _ws_start
        elif name == 'workingSetManager':
            # process all working sets that are new to the file
            for w in sorted(workingSets.keys()):
                if not w in ps.seen_ws:
                    _workingset_open(target, w)
                    for p in workingSets[w]:
                        _workingset_element(target, p)
                    target.close('workingSet')
    
    def _ws_item(name, attributes):
        if name == 'item':
            if ps.current_ws is None:
                target.element(name, attributes)
            else:
                p_name = attributes['elementID'][1:] # strip off the leading '='
                _workingset_element(target, p_name)
                ps.seen_projects.append(p_name)
    
    # process document
    parser.StartElementHandler = _ws_start
    parser.EndElementHandler = _ws_end
    with open(wspath, 'r') as wsfile:
        parser.ParseFile(wsfile)
    
    target.close('workingSetManager')
    return target

def _workingset_open(wsdoc, ws):
    wsdoc.open('workingSet', {'editPageID': 'org.eclipse.jdt.ui.JavaWorkingSetPage', 'factoryID': 'org.eclipse.ui.internal.WorkingSetFactory', 'id': 'wsid_' + ws, 'label': ws, 'name': ws})

def _workingset_element(wsdoc, p):
    wsdoc.element('item', {'elementID': '=' + p, 'factoryID': 'org.eclipse.jdt.ui.PersistableJavaElementFactory'})
    
def netbeansinit(args, suite=None):
    """(re)generate NetBeans project configurations"""

    if suite is None:
        suite = _mainSuite

    def println(out, obj):
        out.write(str(obj) + '\n')

    updated = False
    for p in projects():
        if p.native:
            continue

        if exists(join(p.dir, 'plugin.xml')): # eclipse plugin project
            continue

        if not exists(join(p.dir, 'nbproject')):
            os.makedirs(join(p.dir, 'nbproject'))

        out = XMLDoc()
        out.open('project', {'name' : p.name, 'default' : 'default', 'basedir' : '.'})
        out.element('description', data='Builds, tests, and runs the project ' + p.name + '.')
        out.element('import', {'file' : 'nbproject/build-impl.xml'})
        out.open('target', {'name' : '-post-compile'})
        out.open('exec', { 'executable' : sys.executable})
        out.element('env', {'key' : 'JAVA_HOME', 'value' : java().jdk})
        out.element('arg', {'value' : os.path.abspath(__file__)})
        out.element('arg', {'value' : 'archive'})
        out.element('arg', {'value' : '@GRAAL'})
        out.close('exec')
        out.close('target')
        out.close('project')
        updated = update_file(join(p.dir, 'build.xml'), out.xml(indent='\t', newl='\n')) or updated

        out = XMLDoc()
        out.open('project', {'xmlns' : 'http://www.netbeans.org/ns/project/1'})
        out.element('type', data='org.netbeans.modules.java.j2seproject')
        out.open('configuration')
        out.open('data', {'xmlns' : 'http://www.netbeans.org/ns/j2se-project/3'})
        out.element('name', data=p.name)
        out.element('explicit-platform', {'explicit-source-supported' : 'true'})
        out.open('source-roots')
        out.element('root', {'id' : 'src.dir'})
        if len(p.annotation_processors()) > 0:
            out.element('root', {'id' : 'src.ap-source-output.dir'})
        out.close('source-roots')
        out.open('test-roots')
        out.close('test-roots')
        out.close('data')

        firstDep = True
        for dep in p.all_deps([], True):
            if dep == p:
                continue;

            if not dep.isLibrary():
                n = dep.name.replace('.', '_')
                if firstDep:
                    out.open('references', {'xmlns' : 'http://www.netbeans.org/ns/ant-project-references/1'})
                    firstDep = False

                out.open('reference')
                out.element('foreign-project', data=n)
                out.element('artifact-type', data='jar')
                out.element('script', data='build.xml')
                out.element('target', data='jar')
                out.element('clean-target', data='clean')
                out.element('id', data='jar')
                out.close('reference')

        if not firstDep:
            out.close('references')

        out.close('configuration')
        out.close('project')
        updated = update_file(join(p.dir, 'nbproject', 'project.xml'), out.xml(indent='    ', newl='\n')) or updated

        out = StringIO.StringIO()
        jdkPlatform = 'JDK_' + str(java().version)

        annotationProcessorEnabled = "false"
        annotationProcessorReferences = ""
        annotationProcessorSrcFolder = ""
        if len(p.annotation_processors()) > 0:
            annotationProcessorEnabled = "true"
            annotationProcessorSrcFolder = "src.ap-source-output.dir=${build.generated.sources.dir}/ap-source-output"

        content = """
annotation.processing.enabled=""" + annotationProcessorEnabled + """
annotation.processing.enabled.in.editor=""" + annotationProcessorEnabled + """
annotation.processing.processors.list=
annotation.processing.run.all.processors=true
application.title=""" + p.name + """
application.vendor=mx
build.classes.dir=${build.dir}
build.classes.excludes=**/*.java,**/*.form
# This directory is removed when the project is cleaned:
build.dir=bin
build.generated.dir=${build.dir}/generated
build.generated.sources.dir=${build.dir}/generated-sources
# Only compile against the classpath explicitly listed here:
build.sysclasspath=ignore
build.test.classes.dir=${build.dir}/test/classes
build.test.results.dir=${build.dir}/test/results
# Uncomment to specify the preferred debugger connection transport:
#debug.transport=dt_socket
debug.classpath=\\
    ${run.classpath}
debug.test.classpath=\\
    ${run.test.classpath}
# This directory is removed when the project is cleaned:
dist.dir=dist
dist.jar=${dist.dir}/""" + p.name + """.jar
dist.javadoc.dir=${dist.dir}/javadoc
endorsed.classpath=
excludes=
includes=**
jar.compress=false
# Space-separated list of extra javac options
javac.compilerargs=
javac.deprecation=false
javac.source=1.7
javac.target=1.7
javac.test.classpath=\\
    ${javac.classpath}:\\
    ${build.classes.dir}
javadoc.additionalparam=
javadoc.author=false
javadoc.encoding=${source.encoding}
javadoc.noindex=false
javadoc.nonavbar=false
javadoc.notree=false
javadoc.private=false
javadoc.splitindex=true
javadoc.use=true
javadoc.version=false
javadoc.windowtitle=
main.class=
manifest.file=manifest.mf
meta.inf.dir=${src.dir}/META-INF
mkdist.disabled=false
platforms.""" + jdkPlatform + """.home=""" + java().jdk + """
platform.active=""" + jdkPlatform + """
run.classpath=\\
    ${javac.classpath}:\\
    ${build.classes.dir}
# Space-separated list of JVM arguments used when running the project
# (you may also define separate properties like run-sys-prop.name=value instead of -Dname=value
# or test-sys-prop.name=value to set system properties for unit tests):
run.jvmargs=
run.test.classpath=\\
    ${javac.test.classpath}:\\
    ${build.test.classes.dir}
test.src.dir=./test
""" + annotationProcessorSrcFolder + """
source.encoding=UTF-8""".replace(':', os.pathsep).replace('/', os.sep)
        print >> out, content

        mainSrc = True
        for src in p.srcDirs:
            srcDir = join(p.dir, src)
            if not exists(srcDir):
                os.mkdir(srcDir)
            ref = 'file.reference.' + p.name + '-' + src
            print >> out, ref + '=' + src
            if mainSrc:
                print >> out, 'src.dir=${' + ref + '}'
                mainSrc = False
            else:
                print >> out, 'src.' + src + '.dir=${' + ref + '}'

        javacClasspath = []
        
        deps = p.all_deps([], True)
        annotationProcessorOnlyDeps = []
        if len(p.annotation_processors()) > 0:
            for ap in p.annotation_processors():
                apDep = dependency(ap)
                if not apDep in deps:
                    deps.append(apDep)
                    annotationProcessorOnlyDeps.append(apDep)
        
        annotationProcessorReferences = [];
        
        for dep in deps:
            if dep == p:
                continue;

            if dep.isLibrary():
                if not dep.mustExist:
                    continue
                path = dep.get_path(resolve=True)
                if os.sep == '\\':
                    path = path.replace('\\', '\\\\')
                ref = 'file.reference.' + dep.name + '-bin'
                print >> out, ref + '=' + path

            else:
                n = dep.name.replace('.', '_')
                relDepPath = os.path.relpath(dep.dir, p.dir).replace(os.sep, '/')
                ref = 'reference.' + n + '.jar'
                print >> out, 'project.' + n + '=' + relDepPath
                print >> out, ref + '=${project.' + n + '}/dist/' + dep.name + '.jar'

            if not dep in annotationProcessorOnlyDeps:
                javacClasspath.append('${' + ref + '}')
            else:
                annotationProcessorReferences.append('${' + ref + '}')
                annotationProcessorReferences +=  ":\\\n    ${" + ref + "}"

        print >> out, 'javac.classpath=\\\n    ' + (os.pathsep + '\\\n    ').join(javacClasspath)
        print >> out, 'javac.test.processorpath=${javac.test.classpath}\\\n    ' + (os.pathsep + '\\\n    ').join(annotationProcessorReferences)
        print >> out, 'javac.processorpath=${javac.classpath}\\\n    ' + (os.pathsep + '\\\n    ').join(annotationProcessorReferences)
    
        updated = update_file(join(p.dir, 'nbproject', 'project.properties'), out.getvalue()) or updated
        out.close()

    if updated:
        log('If using NetBeans:')
        log('  1. Ensure that a platform named "JDK_' + str(java().version) + '" is defined (Tools -> Java Platforms)')
        log('  2. Open/create a Project Group for the directory containing the projects (File -> Project Group -> New Group... -> Folder of Projects)')

def ideclean(args, suite=None):
    """remove all Eclipse and NetBeans project configurations"""
    def rm(path):
        if exists(path):
            os.remove(path)

    for p in projects():
        if p.native:
            continue

        shutil.rmtree(join(p.dir, '.settings'), ignore_errors=True)
        shutil.rmtree(join(p.dir, '.externalToolBuilders'), ignore_errors=True)
        shutil.rmtree(join(p.dir, 'nbproject'), ignore_errors=True)
        rm(join(p.dir, '.classpath'))
        rm(join(p.dir, '.project'))
        rm(join(p.dir, '.factorypath'))
        rm(join(p.dir, 'build.xml'))
        rm(join(p.dir, 'eclipse-build.xml'))
        try:
            rm(join(p.dir, p.name + '.jar'))
        except:
            log("Error removing {0}".format(p.name + '.jar'))
            

def ideinit(args, suite=None):
    """(re)generate Eclipse and NetBeans project configurations"""
    eclipseinit(args, suite)
    netbeansinit(args, suite)
    fsckprojects([])

def fsckprojects(args):
    """find directories corresponding to deleted Java projects and delete them"""
    for suite in suites():
        projectDirs = [p.dir for p in suite.projects]
        for root, dirnames, files in os.walk(suite.dir):
            currentDir = join(suite.dir, root)
            if currentDir in projectDirs:
                # don't traverse subdirs of an existing project
                dirnames[:] = []
            else:
                projectConfigFiles = frozenset(['.classpath', 'nbproject'])
                indicators = projectConfigFiles.intersection(files)
                if len(indicators) != 0:
                    if not sys.stdout.isatty() or ask_yes_no(currentDir + ' looks like a removed project -- delete it', 'n'):
                        shutil.rmtree(currentDir)
                        log('Deleted ' + currentDir)

def javadoc(args, parser=None, docDir='javadoc', includeDeps=True):
    """generate javadoc for some/all Java projects"""

    parser = ArgumentParser(prog='mx javadoc') if parser is None else parser
    parser.add_argument('-d', '--base', action='store', help='base directory for output')
    parser.add_argument('--unified', action='store_true', help='put javadoc in a single directory instead of one per project')
    parser.add_argument('--force', action='store_true', help='(re)generate javadoc even if package-list file exists')
    parser.add_argument('--projects', action='store', help='comma separated projects to process (omit to process all projects)')
    parser.add_argument('--Wapi', action='store_true', dest='warnAPI', help='show warnings about using internal APIs')
    parser.add_argument('--argfile', action='store', help='name of file containing extra javadoc options')
    parser.add_argument('--arg', action='append', dest='extra_args', help='extra Javadoc arguments (e.g. --arg @-use)', metavar='@<arg>', default=[])
    parser.add_argument('-m', '--memory', action='store', help='-Xmx value to pass to underlying JVM')
    parser.add_argument('--packages', action='store', help='comma separated packages to process (omit to process all packages)')
    parser.add_argument('--exclude-packages', action='store', help='comma separated packages to exclude')

    args = parser.parse_args(args)

    # build list of projects to be processed
    candidates = sorted_deps()
    if args.projects is not None:
        candidates = [project(name) for name in args.projects.split(',')]

    # optionally restrict packages within a project
    packages = []
    if args.packages is not None:
        packages = [name for name in args.packages.split(',')]

    exclude_packages = []
    if args.exclude_packages is not None:
        exclude_packages = [name for name in args.exclude_packages.split(',')]

    def outDir(p):
        if args.base is None:
            return join(p.dir, docDir)
        return join(args.base, p.name, docDir)

    def check_package_list(p):
        return not exists(join(outDir(p), 'package-list'))

    def assess_candidate(p, projects):
        if p in projects:
            return False
        if args.force or args.unified or check_package_list(p):
            projects.append(p)
            return True
        return False

    projects = []
    for p in candidates:
        if not p.native:
            if includeDeps:
                deps = p.all_deps([], includeLibs=False, includeSelf=False)
                for d in deps:
                    assess_candidate(d, projects)
            if not assess_candidate(p, projects):
                logv('[package-list file exists - skipping {0}]'.format(p.name))


    def find_packages(sourceDirs, pkgs=set()):
        for sourceDir in sourceDirs:
            for root, _, files in os.walk(sourceDir):
                if len([name for name in files if name.endswith('.java')]) != 0:
                    pkg = root[len(sourceDir) + 1:].replace(os.sep,'.')
                    if len(packages) == 0 or pkg in packages:
                        if len(exclude_packages) == 0 or not pkg in exclude_packages:
                            pkgs.add(pkg)
        return pkgs

    extraArgs = [a.lstrip('@') for a in args.extra_args]
    if args.argfile is not None:
        extraArgs += ['@' + args.argfile]
    memory = '2g'
    if args.memory is not None:
        memory = args.memory
    memory = '-J-Xmx' + memory

    if not args.unified:
        for p in projects:
            # The project must be built to ensure javadoc can find class files for all referenced classes
            build(['--no-native', '--projects', p.name])
            
            pkgs = find_packages(p.source_dirs(), set())
            deps = p.all_deps([], includeLibs=False, includeSelf=False)
            links = ['-link', 'http://docs.oracle.com/javase/' + str(p.javaCompliance.value) + '/docs/api/']
            out = outDir(p)
            for d in deps:
                depOut = outDir(d)
                links.append('-link')
                links.append(os.path.relpath(depOut, out))
            cp = classpath(p.name, includeSelf=True)
            sp = os.pathsep.join(p.source_dirs())
            overviewFile = join(p.dir, 'overview.html')
            delOverviewFile = False
            if not exists(overviewFile):
                with open(overviewFile, 'w') as fp:
                    print >> fp, '<html><body>Documentation for the <code>' + p.name + '</code> project.</body></html>'
                delOverviewFile = True
            nowarnAPI = []
            if not args.warnAPI:
                nowarnAPI.append('-XDignore.symbol.file')
            try:
                log('Generating {2} for {0} in {1}'.format(p.name, out, docDir))
                run([java().javadoc, memory,
                     '-windowtitle', p.name + ' javadoc',
                     '-XDignore.symbol.file',
                     '-classpath', cp,
                     '-quiet',
                     '-d', out,
                     '-overview', overviewFile,
                     '-sourcepath', sp] +
                     links +
                     extraArgs +
                     nowarnAPI +
                     list(pkgs))
                log('Generated {2} for {0} in {1}'.format(p.name, out, docDir))
            finally:
                if delOverviewFile:
                    os.remove(overviewFile)
                
    else:
        # The projects must be built to ensure javadoc can find class files for all referenced classes
        build(['--no-native'])
        
        pkgs = set()
        sp = []
        names = []
        for p in projects:
            find_packages(p.source_dirs(), pkgs)
            sp += p.source_dirs()
            names.append(p.name)

        links = ['-link', 'http://docs.oracle.com/javase/' + str(_java.javaCompliance.value) + '/docs/api/']
        out = join(_mainSuite.dir, docDir)
        if args.base is not None:
            out = join(args.base, docDir)
        cp = classpath()
        sp = os.pathsep.join(sp)
        nowarnAPI = []
        if not args.warnAPI:
            nowarnAPI.append('-XDignore.symbol.file')
        log('Generating {2} for {0} in {1}'.format(', '.join(names), out, docDir))
        run([java().javadoc, memory,
             '-classpath', cp,
             '-quiet',
             '-d', out,
             '-sourcepath', sp] +
             links +
             extraArgs +
             nowarnAPI +
             list(pkgs))
        log('Generated {2} for {0} in {1}'.format(', '.join(names), out, docDir))

class Chunk:
    def __init__(self, content, ldelim, rdelim=None):
        lindex = content.find(ldelim)
        if rdelim is not None:
            rindex = content.find(rdelim)
        else:
            rindex = lindex + len(ldelim)
        self.ldelim = ldelim
        self.rdelim = rdelim
        if lindex != -1 and rindex != -1 and rindex > lindex:
            self.text = content[lindex + len(ldelim):rindex]
        else:
            self.text = None

    def replace(self, content, repl):
        lindex = content.find(self.ldelim)
        if self.rdelim is not None:
            rindex = content.find(self.rdelim)
            rdelimLen = len(self.rdelim)
        else:
            rindex = lindex + len(self.ldelim)
            rdelimLen = 0
        old = content[lindex:rindex + rdelimLen]
        return content.replace(old, repl)
        
# Post-process an overview-summary.html file to move the
# complete overview to the top of the page
def _fix_overview_summary(path, topLink):
    """
    Processes an "overview-summary.html" generated by javadoc to put the complete
    summary text above the Packages table.
    """

    # This uses scraping and so will break if the relevant content produced by javadoc changes in any way!
    with open(path) as fp:
        content = fp.read()

    chunk1 = Chunk(content, """<div class="header">
<div class="subTitle">
<div class="block">""", """</div>
</div>
<p>See: <a href="#overview_description">Description</a></p>
</div>""")

    chunk2 = Chunk(content, """<div class="footer"><a name="overview_description">
<!--   -->
</a>
<div class="subTitle">
<div class="block">""", """</div>
</div>
</div>
<!-- ======= START OF BOTTOM NAVBAR ====== -->""")

    assert chunk1.text, 'Could not find header section in ' + path
    assert chunk2.text, 'Could not find footer section in ' + path

    content = chunk1.replace(content, '<div class="header"><div class="subTitle"><div class="block">' + topLink + chunk2.text +'</div></div></div>')
    content = chunk2.replace(content, '')

    with open(path, 'w') as fp:
        fp.write(content)


# Post-process a package-summary.html file to move the
# complete package description to the top of the page
def _fix_package_summary(path):
    """
    Processes an "overview-summary.html" generated by javadoc to put the complete
    summary text above the Packages table.
    """

    # This uses scraping and so will break if the relevant content produced by javadoc changes in any way!
    with open(path) as fp:
        content = fp.read()

    chunk1 = Chunk(content, """<div class="header">
<h1 title="Package" class="title">Package""", """<p>See:&nbsp;<a href="#package_description">Description</a></p>
</div>""")

    chunk2 = Chunk(content, """<a name="package_description">
<!--   -->
</a>""", """</div>
</div>
<!-- ======= START OF BOTTOM NAVBAR ====== -->""")

    if chunk1.text:
        if chunk2.text:
            repl = re.sub(r'<h2 title=(.*) Description</h2>', r'<h1 title=\1</h1>', chunk2.text, 1)
            content = chunk1.replace(content, '<div class="header">' + repl +'</div></div>')
            content = chunk2.replace(content, '')
        
            with open(path, 'w') as fp:
                fp.write(content)
        else:
            log('warning: Could not find package description detail section in ' + path)

    else:
        # no package description given
        pass

def site(args):
    """creates a website containing javadoc and the project dependency graph"""

    parser = ArgumentParser(prog='site')
    parser.add_argument('-d', '--base', action='store', help='directory for generated site', required=True, metavar='<dir>')
    parser.add_argument('--name', action='store', help='name of overall documentation', required=True, metavar='<name>')
    parser.add_argument('--overview', action='store', help='path to the overview content for overall documentation', required=True, metavar='<path>')
    parser.add_argument('--projects', action='store', help='comma separated projects to process (omit to process all projects)')
    parser.add_argument('--jd', action='append', help='extra Javadoc arguments (e.g. --jd @-use)', metavar='@<arg>', default=[])
    parser.add_argument('--exclude-packages', action='store', help='comma separated packages to exclude', metavar='<pkgs>')
    parser.add_argument('--dot-output-base', action='store', help='base file name (relative to <dir>/all) for project dependency graph .svg and .jpg files generated by dot (omit to disable dot generation)', metavar='<path>')
    parser.add_argument('--title', action='store', help='value used for -windowtitle and -doctitle javadoc args for overall documentation (default: "<name>")', metavar='<title>')
    args = parser.parse_args(args)

    args.base = os.path.abspath(args.base)
    tmpbase = tempfile.mkdtemp(prefix=basename(args.base) + '.', dir=dirname(args.base))
    unified = join(tmpbase, 'all')

    exclude_packages_arg = []
    if args.exclude_packages is not None:
        exclude_packages_arg = ['--exclude-packages', args.exclude_packages]

    projects = sorted_deps()
    projects_arg = []
    if args.projects is not None:
        projects_arg = ['--projects', args.projects]
        projects = [project(name) for name in args.projects.split(',')]

    extra_javadoc_args = []
    for a in args.jd:
        extra_javadoc_args.append('--arg')
        extra_javadoc_args.append('@' + a)

    try:
        # Create javadoc for each project
        javadoc(['--base', tmpbase] + exclude_packages_arg + projects_arg + extra_javadoc_args)

        # Create unified javadoc for all projects
        with open(args.overview) as fp:
            content = fp.read()
            idx = content.rfind('</body>')
            if idx != -1:
                args.overview = join(tmpbase, 'overview_with_projects.html')
                with open(args.overview, 'w') as fp2:
                    print >> fp2, content[0:idx]
                    print >> fp2, """<div class="contentContainer">
<table class="overviewSummary" border="0" cellpadding="3" cellspacing="0" summary="Projects table">
<caption><span>Projects</span><span class="tabEnd">&nbsp;</span></caption>
<tr><th class="colFirst" scope="col">Project</th><th class="colLast" scope="col">&nbsp;</th></tr>
<tbody>"""
                    color = 'row'
                    for p in projects:
                        print >> fp2, '<tr class="{1}Color"><td class="colFirst"><a href="../{0}/javadoc/index.html", target = "_top">{0}</a></td><td class="colLast">&nbsp;</td></tr>'.format(p.name, color)
                        color = 'row' if color == 'alt' else 'alt'
                        
                    print >> fp2, '</tbody></table></div>'
                    print >> fp2, content[idx:]
        
        title = args.title if args.title is not None else args.name
        javadoc(['--base', tmpbase,
                 '--unified',
                 '--arg', '@-windowtitle', '--arg', '@' + title,
                 '--arg', '@-doctitle', '--arg', '@' + title,
                 '--arg', '@-overview', '--arg', '@' + args.overview] + exclude_packages_arg + projects_arg + extra_javadoc_args)
        os.rename(join(tmpbase, 'javadoc'), unified)

        # Generate dependency graph with Graphviz
        if args.dot_output_base is not None:
            dotErr = None
            try:
                if not 'version' in subprocess.check_output(['dot', '-V'], stderr=subprocess.STDOUT):
                    dotErr = 'dot -V does not print a string containing "version"'
            except subprocess.CalledProcessError as e:
                dotErr = 'error calling "dot -V": {}'.format(e) 
            except OSError as e:
                dotErr = 'error calling "dot -V": {}'.format(e)
                 
            if dotErr != None:
                abort('cannot generate dependency graph: ' + dotErr)

            dot = join(tmpbase, 'all', str(args.dot_output_base) + '.dot')
            svg = join(tmpbase, 'all', str(args.dot_output_base) + '.svg')
            jpg = join(tmpbase, 'all', str(args.dot_output_base) + '.jpg')
            html = join(tmpbase, 'all', str(args.dot_output_base) + '.html')
            with open(dot, 'w') as fp:
                dim = len(projects)
                print >> fp, 'digraph projects {'
                print >> fp, 'rankdir=BT;'
                print >> fp, 'size = "' + str(dim) + ',' + str(dim) + '";'
                print >> fp, 'node [shape=rect, fontcolor="blue"];'
                #print >> fp, 'edge [color="green"];'
                for p in projects:
                    print >> fp, '"' + p.name + '" [URL = "../' + p.name + '/javadoc/index.html", target = "_top"]'
                    for dep in p.canonical_deps():
                        if dep in [proj.name for proj in projects]:
                            print >> fp, '"' + p.name + '" -> "' + dep + '"'
                depths = dict()
                for p in projects:
                    d = p.max_depth()
                    depths.setdefault(d, list()).append(p.name)
                print >> fp, '}'
    
            run(['dot', '-Tsvg', '-o' + svg, '-Tjpg', '-o' + jpg, dot])

            # Post-process generated SVG to remove title elements which most browsers
            # render as redundant (and annoying) tooltips.
            with open(svg, 'r') as fp:
                content = fp.read()
            content = re.sub('<title>.*</title>', '', content)
            content = re.sub('xlink:title="[^"]*"', '', content)
            with open(svg, 'w') as fp:
                fp.write(content)
        
            # Create HTML that embeds the svg file in an <object> frame
            with open(html, 'w') as fp:
                print >> fp, '<html><body><object data="{}.svg" type="image/svg+xml"></object></body></html>'.format(args.dot_output_base)

        top = join(tmpbase, 'all', 'overview-summary.html')
        for root, _, files in os.walk(tmpbase):
            for f in files:
                if f == 'overview-summary.html':
                    path = join(root, f)
                    topLink = ''
                    if top != path:
                        link = os.path.relpath(join(tmpbase, 'all', 'index.html'), dirname(path))
                        topLink = '<p><a href="' + link + '", target="_top"><b>[return to the overall ' + args.name + ' documentation]</b></a></p>'
                    _fix_overview_summary(path, topLink)
                elif f == 'package-summary.html':
                    path = join(root, f)
                    _fix_package_summary(path)


        if exists(args.base):
            shutil.rmtree(args.base)
        shutil.move(tmpbase, args.base)

        print 'Created website - root is ' + join(args.base, 'all', 'index.html')

    finally:
        if exists(tmpbase):
            shutil.rmtree(tmpbase)

def findclass(args, logToConsole=True):
    """find all classes matching a given substring"""

    matches = []
    for entry, filename in classpath_walk(includeBootClasspath=True):
        if filename.endswith('.class'):
            if isinstance(entry, zipfile.ZipFile):
                classname = filename.replace('/', '.')
            else:
                classname = filename.replace(os.sep, '.')
            classname = classname[:-len('.class')]
            for a in args:
                if a in classname:
                    matches.append(classname)
                    if logToConsole:
                        log(classname)
    return matches

def select_items(items, descriptions=None, allowMultiple=True):
    """
    Presents a command line interface for selecting one or more (if allowMultiple is true) items.
    
    """
    if len(items) <= 1:
        return items
    else:
        if allowMultiple:
            log('[0] <all>')
        for i in range(0, len(items)):
            if descriptions is None:
                log('[{0}] {1}'.format(i + 1, items[i]))
            else:
                assert len(items) == len(descriptions)
                wrapper = textwrap.TextWrapper(subsequent_indent='    ')
                log('\n'.join(wrapper.wrap('[{0}] {1} - {2}'.format(i + 1, items[i], descriptions[i]))))
        while True:
            if allowMultiple:
                s = raw_input('Enter number(s) of selection (separate multiple choices with spaces): ').split()
            else:
                s = [raw_input('Enter number of selection: ')]
            try:
                s = [int(x) for x in s]
            except:
                log('Selection contains non-numeric characters: "' + ' '.join(s) + '"')
                continue
            
            if allowMultiple and 0 in s:
                return items
            
            indexes = []
            for n in s:
                if n not in range(1, len(items) + 1):
                    log('Invalid selection: ' + str(n))
                    continue
                else:
                    indexes.append(n - 1)
            if allowMultiple:
                return [items[i] for i in indexes]
            if len(indexes) == 1:
                return items[indexes[0]]
            return None

def javap(args):
    """disassemble classes matching given pattern with javap"""

    javap = java().javap
    if not exists(javap):
        abort('The javap executable does not exists: ' + javap)
    else:
        candidates = findclass(args, logToConsole=False)
        if len(candidates) == 0:
            log('no matches')
        selection = select_items(candidates)
        run([javap, '-private', '-verbose', '-classpath', classpath()] + selection)

def show_projects(args):
    """show all loaded projects"""
    for s in suites():
        projectsFile = join(s.dir, 'mx', 'projects')
        if exists(projectsFile):
            log(projectsFile)
            for p in s.projects:
                log('\t' + p.name)
                
def ask_yes_no(question, default=None):
    """"""
    assert not default or default == 'y' or default == 'n'
    if not sys.stdout.isatty():
        if default:
            return default
        else:
            abort("Can not answer '" + question + "?' if stdout is not a tty")
    questionMark = '? [yn]: '
    if default:
        questionMark = questionMark.replace(default, default.upper())
    answer = raw_input(question + questionMark) or default
    while not answer:
        answer = raw_input(question + questionMark)
    return answer.lower().startswith('y')

def add_argument(*args, **kwargs):
    """
    Define how a single command-line argument.
    """
    assert _argParser is not None
    _argParser.add_argument(*args, **kwargs)

# Table of commands in alphabetical order.
# Keys are command names, value are lists: [<function>, <usage msg>, <format args to doc string of function>...]
# If any of the format args are instances of Callable, then they are called with an 'env' are before being
# used in the call to str.format().
# Extensions should update this table directly
commands = {
    'about': [about, ''],
    'build': [build, '[options]'],
    'checkstyle': [checkstyle, ''],
    'canonicalizeprojects': [canonicalizeprojects, ''],
    'clean': [clean, ''],
    'eclipseinit': [eclipseinit, ''],
    'eclipseformat': [eclipseformat, ''],
    'findclass': [findclass, ''],
    'fsckprojects': [fsckprojects, ''],
    'help': [help_, '[command]'],
    'ideclean': [ideclean, ''],
    'ideinit': [ideinit, ''],
    'archive': [archive, '[options]'],
    'projectgraph': [projectgraph, ''],
    'javap': [javap, '<class name patterns>'],
    'javadoc': [javadoc, '[options]'],
    'site': [site, '[options]'],
    'netbeansinit': [netbeansinit, ''],
    'projects': [show_projects, ''],
}

_argParser = ArgParser()

def _findPrimarySuite():
    def is_suite_dir(d):
        mxDir = join(d, 'mx')
        if exists(mxDir) and isdir(mxDir) and exists(join(mxDir, 'projects')):
            return dirname(mxDir)

    # try current working directory first
    if is_suite_dir(os.getcwd()):
        return os.getcwd()

    # now search path of my executable
    me = sys.argv[0]
    parent = dirname(me)
    while parent:
        if is_suite_dir(parent):
            return parent
        parent = dirname(parent)
    return None

def main():
    primarySuiteDir = _findPrimarySuite()
    if primarySuiteDir:
        global _mainSuite
        _mainSuite = _loadSuite(primarySuiteDir, True)

    opts, commandAndArgs = _argParser._parse_cmd_line()

    global _opts, _java
    _opts = opts
    _java = JavaConfig(opts)

    for s in suites():
        s._post_init(opts)

    if len(commandAndArgs) == 0:
        _argParser.print_help()
        return

    command = commandAndArgs[0]
    command_args = commandAndArgs[1:]

    if not commands.has_key(command):
        hits = [c for c in commands.iterkeys() if c.startswith(command)]
        if len(hits) == 1:
            command = hits[0]
        elif len(hits) == 0:
            abort('mx: unknown command \'{0}\'\n{1}use "mx help" for more options'.format(command, _format_commands()))
        else:
            abort('mx: command \'{0}\' is ambiguous\n    {1}'.format(command, ' '.join(hits)))

    c, _ = commands[command][:2]
    def term_handler(signum, frame):
        abort(1)
    signal.signal(signal.SIGTERM, term_handler)
    try:
        if opts.timeout != 0:
            def alarm_handler(signum, frame):
                abort('Command timed out after ' + str(opts.timeout) + ' seconds: ' + ' '.join(commandAndArgs))
            signal.signal(signal.SIGALRM, alarm_handler)
            signal.alarm(opts.timeout)
        retcode = c(command_args)
        if retcode is not None and retcode != 0:
            abort(retcode)
    except KeyboardInterrupt:
        # no need to show the stack trace when the user presses CTRL-C
        abort(1)

if __name__ == '__main__':
    # rename this module as 'mx' so it is not imported twice by the commands.py modules
    sys.modules['mx'] = sys.modules.pop('__main__')

    main()
