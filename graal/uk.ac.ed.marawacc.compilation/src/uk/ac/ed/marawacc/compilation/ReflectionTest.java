package uk.ac.ed.marawacc.compilation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import com.oracle.graal.nodes.StructuredGraph;

public class ReflectionTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean isCompiledGraph(long id) throws ClassNotFoundException, IllegalAccessException,
                    IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException, MalformedURLException {
        URLClassLoader clsLoader = URLClassLoader.newInstance(new URL[]{new URL("file:/home/juan/phd/astx-compiler/jvmci/jdk1.8.0_91/product/jre/lib/jvmci/graal-compiler.jar")});
        Class cls = clsLoader.loadClass("uk.ac.ed.marawacc.compilation.MarawaccGraalIR");
        Method method = cls.getMethod("getInstance");
        Object marawacc = method.invoke(null);
        Method isCompiledGraph = cls.getMethod("isCompiledGraph", long.class);
        boolean b = (boolean) isCompiledGraph.invoke(marawacc, id);
        return b;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void updateGraph(StructuredGraph graph, long id) throws ClassNotFoundException, IllegalAccessException,
                    IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException, MalformedURLException {
        URLClassLoader clsLoader = URLClassLoader.newInstance(new URL[]{new URL("file:/home/juan/phd/astx-compiler/jvmci/jdk1.8.0_91/product/jre/lib/jvmci/graal-compiler.jar")});
        Class cls = clsLoader.loadClass("uk.ac.ed.marawacc.compilation.MarawaccGraalIR");
        Method method = cls.getMethod("getInstance");
        Object marawacc = method.invoke(null);
        Method updateGraph = cls.getMethod("updateGraph", StructuredGraph.class, long.class);
        updateGraph.invoke(marawacc, graph, id);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void preloadSingleton() throws ClassNotFoundException, IllegalAccessException,
                    IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException, MalformedURLException {
        URLClassLoader clsLoader = URLClassLoader.newInstance(new URL[]{new URL("file:/home/juan/phd/astx-compiler/jvmci/jdk1.8.0_91/product/jre/lib/jvmci/graal-compiler.jar")});
        Class cls = clsLoader.loadClass("uk.ac.ed.marawacc.compilation.MarawaccGraalIR");
        Method method = cls.getMethod("getInstance");
        Object marawacc = method.invoke(null);
        Method m2 = cls.getMethod("printInfo");
        m2.invoke(marawacc);
    }

    public static void customClassLoader() {
        List<URL> classpaths = new ArrayList<>();
        try {
            classpaths.add(new URL("file:/home/juan/phd/astx-compiler/jvmci/jdk1.8.0_91/product/jre/lib/jvmci/graal-compiler.jar"));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

        CustomClassLoader ccl = new CustomClassLoader(classpaths);

        try {
            ccl.loadClass("com.oracle.graal.nodes.StructuredGraph");
            ccl.loadClass("uk.ac.ed.marawacc.compilation.MarawaccGraalIR");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void insertCallTargetID(StructuredGraph graph, long id) throws ClassNotFoundException, IllegalAccessException,
                    IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException, MalformedURLException {
        URLClassLoader clsLoader = URLClassLoader.newInstance(new URL[]{new URL("file:/home/juan/phd/astx-compiler/jvmci/jdk1.8.0_91/product/jre/lib/jvmci/graal-compiler.jar")});
        Class cls = clsLoader.loadClass("uk.ac.ed.marawacc.compilation.MarawaccGraalIR");
        Method method = cls.getMethod("getInstance");
        Object marawacc = method.invoke(null);

        Method printInfo = cls.getMethod("printInfo");
        printInfo.invoke(marawacc);

        Method insertCallTargetID = cls.getMethod("insertCallTargetID", long.class, long.class);
        insertCallTargetID.invoke(marawacc, graph.graphId(), id);
    }

}
