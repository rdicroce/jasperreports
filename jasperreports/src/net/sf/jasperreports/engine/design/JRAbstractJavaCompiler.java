/*
 * JasperReports - Free Java Reporting Library.
 * Copyright (C) 2001 - 2019 TIBCO Software Inc. All rights reserved.
 * http://www.jaspersoft.com
 *
 * Unless you have purchased a commercial license agreement from Jaspersoft,
 * the following license terms apply:
 *
 * This program is part of JasperReports.
 *
 * JasperReports is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JasperReports is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with JasperReports. If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * Contributors:
 * Peter Severin - peter_p_s@users.sourceforge.net 
 */
package net.sf.jasperreports.engine.design;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import org.apache.commons.collections4.map.ReferenceMap;

import net.sf.jasperreports.annotations.properties.Property;
import net.sf.jasperreports.annotations.properties.PropertyScope;
import net.sf.jasperreports.compilers.DirectExpressionValueFilter;
import net.sf.jasperreports.compilers.JavaDirectExpressionValueFilter;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JRRuntimeException;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.fill.JREvaluator;
import net.sf.jasperreports.engine.util.JRClassLoader;
import net.sf.jasperreports.properties.PropertyConstants;


/**
 * @author Teodor Danciu (teodord@users.sourceforge.net)
 */
public abstract class JRAbstractJavaCompiler extends JRAbstractCompiler
{

	/**
	 * Property that indicates whether a legacy fix for a JVM issue related to
	 * evaluator classes generated by JasperReports is enabled.  The fix is
	 * enabled by default.
	 * 
	 * Due to the fix, the garbage collector might not be able to collect
	 * a classloader that loaded JasperReports classes. This would be
	 * inconvenient in scenarios in which JasperReports classes are repeatedly
	 * loaded by different classloaders, e.g. when JasperReports is part of
	 * the classpath of a web application which is often reloaded.  In such
	 * scenarios, set this property to false.
	 */
	@Property(
			category = PropertyConstants.CATEGORY_FILL,
			defaultValue = PropertyConstants.BOOLEAN_TRUE,
			scopes = {PropertyScope.CONTEXT},
			sinceVersion = PropertyConstants.VERSION_3_0_0,
			valueType = Boolean.class
			)
	public static final String PROPERTY_EVALUATOR_CLASS_REFERENCE_FIX_ENABLED = JRPropertiesUtil.PROPERTY_PREFIX + 
			"evaluator.class.reference.fix.enabled";
	
	public static final String EXCEPTION_MESSAGE_KEY_EXPECTED_JAVA_LANGUAGE = "compilers.language.expected.java";
	public static final String EXCEPTION_MESSAGE_KEY_EXPRESSION_CLASS_NOT_LOADED = "compilers.expression.class.not.loaded";

	// @JVM Crash workaround
	// Reference to the loaded class class in a per thread map
	private static ThreadLocal<Class<?>> classFromBytesRef = new ThreadLocal<>();


	private static final Object CLASS_CACHE_NULL_KEY = new Object();
	private static Map<Object,Map<String,Class<?>>> classCache = 
		new ReferenceMap<>(
			ReferenceMap.ReferenceStrength.WEAK, ReferenceMap.ReferenceStrength.SOFT
			);
	
	/**
	 * 
	 */
	protected JRAbstractJavaCompiler(JasperReportsContext jasperReportsContext, boolean needsSourceFiles)
	{
		super(jasperReportsContext, needsSourceFiles);
	}

	@Override
	protected DirectExpressionValueFilter directValueFilter()
	{
		return JavaDirectExpressionValueFilter.instance();
	}

	@Override
	protected JREvaluator loadEvaluator(Serializable compileData, String className) throws JRException
	{
		JREvaluator evaluator = null;

		try
		{
			Class<?> clazz = getClassFromCache(className);
			if (clazz == null)
			{
				CompiledClasses compiledClasses = toCompiledClasses(className, compileData);
				clazz = loadClass(className, compiledClasses);
				putClassInCache(className, clazz);
			}
			
			if (JRPropertiesUtil.getInstance(jasperReportsContext).getBooleanProperty(PROPERTY_EVALUATOR_CLASS_REFERENCE_FIX_ENABLED))
			{
				//FIXME multiple classes per thread?
				classFromBytesRef.set(clazz);
			}
		
			evaluator = (JREvaluator) clazz.getDeclaredConstructor().newInstance();
		}
		catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException | InstantiationException e)
		{
			throw 
			new JRException(
				EXCEPTION_MESSAGE_KEY_EXPRESSION_CLASS_NOT_LOADED, 
				new Object[]{className}, 
				e);
		}
		
		return evaluator;
	}
	
	protected CompiledClasses toCompiledClasses(String className, Serializable compileData)
	{
		CompiledClasses classes;
		if (compileData instanceof CompiledClasses)
		{
			classes = (CompiledClasses) compileData;
		}
		else if (compileData instanceof byte[])
		{
			classes = CompiledClasses.forClass(className, (byte[]) compileData);
		}
		else
		{
			throw new JRRuntimeException("Unknown compile data type " + compileData.getClass());
		}
		return classes;
	}


	protected Class<?> loadClass(String className, byte[] compileData)
	{
		return JRClassLoader.loadClassFromBytes(reportClassFilter, className, compileData);
	}


	protected Class<?> loadClass(String className, CompiledClasses classes)
	{
		return JRClassLoader.loadClassFromBytes(reportClassFilter, className, classes);
	}
	
	
	protected static Object classCacheKey()
	{
		ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
		return contextClassLoader == null ? CLASS_CACHE_NULL_KEY : contextClassLoader;
	}

	
	protected static synchronized Class<?> getClassFromCache(String className)
	{
		Object key = classCacheKey();
		Map<String,Class<?>> contextMap = classCache.get(key);
		Class<?> cachedClass = null;
		if (contextMap != null)
		{
			cachedClass = contextMap.get(className);
		}
		return cachedClass;
	}


	protected static synchronized void putClassInCache(String className, Class<?> loadedClass)
	{
		Object key = classCacheKey();
		Map<String,Class<?>> contextMap = classCache.get(key);
		if (contextMap == null)
		{
			contextMap = new ReferenceMap<>(ReferenceMap.ReferenceStrength.HARD, ReferenceMap.ReferenceStrength.SOFT);
			classCache.put(key, contextMap);
		}
		contextMap.put(className, loadedClass);
	}
}
