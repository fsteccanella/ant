/*
 * Copyright (C) The Apache Software Foundation. All rights reserved.
 *
 * This software is published under the terms of the Apache Software License
 * version 1.1, a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */
package org.apache.tools.todo.taskdefs;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.apache.aut.nativelib.Os;
import org.apache.myrmidon.api.TaskContext;
import org.apache.myrmidon.api.TaskException;
import org.apache.myrmidon.framework.Execute;
import org.apache.tools.todo.types.Commandline;
import org.apache.tools.todo.types.EnvironmentData;
import org.apache.tools.todo.types.Path;
import org.apache.tools.todo.types.PathUtil;
import org.apache.tools.todo.types.SysProperties;
import org.apache.tools.todo.util.FileUtils;

/**
 * A utility class that executes a Java app, either in this JVM, or a forked
 * JVM.
 *
 * @author thomas.haas@softwired-inc.com
 * @author <a href="mailto:stefan.bodewig@epost.de">Stefan Bodewig</a>
 */
public class ExecuteJava
{
    private final Path m_classPath = new Path();
    private final EnvironmentData m_sysProperties = new EnvironmentData();
    private final Commandline m_args = new Commandline();
    private final Commandline m_vmArgs = new Commandline();
    private boolean m_fork;
    private File m_workingDirectory;
    private File m_jar;
    private String m_jvm;
    private String m_className;
    private String m_maxMemory;
    private boolean m_ignoreReturnCode;

    public void setClassName( final String className )
    {
        m_className = className;
    }

    public void setJar( final File jar )
    {
        m_jar = jar;
    }

    public void setFork( final boolean fork )
    {
        m_fork = fork;
    }

    /**
     * Sets the max memory to use when running the application in a forked JVM.
     *
     * @param maxMemory the maximum memory, or null for the default.
     */
    public void setMaxMemory( final String maxMemory )
    {
        m_maxMemory = maxMemory;
    }

    public void setWorkingDirectory( final File workingDirectory )
    {
        m_workingDirectory = workingDirectory;
    }

    public void setIgnoreReturnCode( boolean ignore )
    {
        m_ignoreReturnCode = ignore;
    }

    /**
     * Sets the JVM executable to use to run the application in a forked JVM.
     *
     * @param jvm the path to the JVM executable, or null to use the default
     *            JVM executable.
     */
    public void setJvm( final String jvm )
    {
        m_jvm = jvm;
    }

    public Path getClassPath()
    {
        return m_classPath;
    }

    public EnvironmentData getSysProperties()
    {
        return m_sysProperties;
    }

    public Commandline getArguments()
    {
        return m_args;
    }

    public Commandline getVmArguments()
    {
        return m_vmArgs;
    }

    /**
     * Executes the application.
     */
    public void execute( final TaskContext context )
        throws TaskException
    {
        if( m_fork )
        {
            executeForked( context );
        }
        else
        {
            executeNonForked( context );
        }
    }

    /**
     * Executes the application in this JVM.
     */
    public void executeNonForked( final TaskContext context )
        throws TaskException
    {
        if( m_className == null )
        {
            throw new TaskException( "Classname must not be null." );
        }
        if( m_jar != null )
        {
            throw new TaskException( "Cannot execute a jar in non-forked mode." );
        }
        if( m_vmArgs.size() > 0 )
        {
            context.warn( "JVM args ignored when same JVM is used." );
        }
        if( m_workingDirectory != null )
        {
            context.warn( "Working directory ignored when same JVM is used." );
        }
        if( m_sysProperties.size() > 0 )
        {
            context.warn( "System properties ignored when same JVM is used." );
        }

        final String[] args = m_args.getArguments();
        context.debug( "Running in same VM: " + m_className + " " + FileUtils.formatCommandLine( args ) );

        // Locate the class
        final Class target;
        try
        {
            final URL[] urls = PathUtil.toURLs( m_classPath, context );
            if( urls.length == 0 )
            {
                target = Class.forName( m_className );
            }
            else
            {
                final URLClassLoader classLoader = new URLClassLoader( urls );
                target = classLoader.loadClass( m_className );
            }
        }
        catch( final Exception e )
        {
            throw new TaskException( "Could not find class \"" + m_className + "\".", e );
        }

        // Call the main method
        try
        {
            final Class[] params = { args.getClass() };
            final Method main = target.getMethod( "main", params );
            main.invoke( null, new Object[] { args } );
        }
        catch( final InvocationTargetException e )
        {
            final Throwable t = e.getTargetException();
            throw new TaskException( "Could not execute class \"" + m_className + "\".", t );
        }
        catch( final Exception e )
        {
            throw new TaskException( "Could not execute class \"" + m_className + "\".", e );
        }
    }

    /**
     * Executes the application in a separate JVM.
     */
    public int executeForked( final TaskContext context )
        throws TaskException
    {
        // Validate
        if( m_className != null && m_jar != null )
        {
            throw new TaskException( "Only one of Classname and Jar can be set." );
        }
        else if( m_className == null && m_jar == null )
        {
            throw new TaskException( "Classname must not be null." );
        }

        final Execute exe = new Execute();
        exe.setWorkingDirectory( m_workingDirectory );
        exe.setIgnoreReturnCode( m_ignoreReturnCode );

        // Setup the command line
        final Commandline command = exe.getCommandline();

        // Executable name
        if( m_jvm != null )
        {
            command.setExecutable( m_jvm );
        }
        else
        {
            command.setExecutable( getJavaExecutableName() );
        }

        // JVM arguments
        command.addArguments( m_vmArgs );

        // Max memory size
        if( m_maxMemory != null )
        {
            command.addArgument( "-Xmx" + m_maxMemory );
        }

        // System properties
        final String[] props = SysProperties.getJavaVariables( m_sysProperties );
        command.addArguments( props );

        // Classpath
        final String[] classpath = m_classPath.listFiles( context );
        if( classpath.length > 0 )
        {
            command.addArgument( "-classpath" );
            command.addArgument( PathUtil.formatPath( classpath ) );
        }

        // What to execute
        if( m_jar != null )
        {
            command.addArgument( "-jar" );
            command.addArgument( m_jar );
        }
        else
        {
            command.addArgument( m_className );
        }

        // Java app arguments
        command.addArguments( m_args );

        // Execute
        return exe.execute( context );
    }

    /**
     * Determines the executable name for the java command for this JVM.
     *
     * @todo Move this to somewhere in AUT.
     */
    public static String getJavaExecutableName()
    {
        if( Os.isFamily( Os.OS_FAMILY_NETWARE ) )
        {
            // NetWare may have a "java" in the JRE directory, but 99% of
            // the time, you don't want to execute it -- Jeff Tulley
            // <JTULLEY@novell.com>
            return "java";
        }

        // Figure out the basename
        final String baseName;
        if( Os.isFamily( Os.OS_FAMILY_WINDOWS) || Os.isFamily( Os.OS_FAMILY_DOS ) )
        {
            baseName = "java.exe";
        }
        else
        {
            baseName = "java";
        }

        // Look for java in the ${java.home{/../bin directory.  Unfortunately
        // on Windows java.home doesn't always refer to the correct location,
        // so we need to fall back to assuming java is somewhere on the
        // PATH.
        File javaExe =
            new File( System.getProperty( "java.home" ) + "/../bin/" + baseName );

        if( javaExe.exists() )
        {
            return javaExe.getAbsolutePath();
        }
        else
        {
            return "java";
        }
    }
}
