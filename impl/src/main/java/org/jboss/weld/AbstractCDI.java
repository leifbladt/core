/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld;

import static org.jboss.weld.util.reflection.Reflections.cast;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.Set;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.inject.spi.Unmanaged;
import javax.enterprise.util.TypeLiteral;

import org.jboss.weld.bean.builtin.BeanManagerProxy;
import org.jboss.weld.logging.BeanManagerLogger;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.util.cache.ComputingCache;
import org.jboss.weld.util.cache.ComputingCacheBuilder;
import org.jboss.weld.util.collections.ImmutableSet;

/**
 * Abstract implementation of CDI which forwards all Instance methods to a delegate. Furthermore, it allows the calling class to be identified using the
 * {@link #getCallingClassName()} method.
 *
 * @author Jozef Hartinger
 *
 * @param <T>
 */
public abstract class AbstractCDI<T> extends CDI<T> {

    // CDI API / IMPL classes calling CDI.current()
    // used for caller detection
    protected final Set<String> knownClassNames;

    private final ComputingCache<BeanManagerImpl, Instance<T>> instanceCache;

    public AbstractCDI() {
        ImmutableSet.Builder<String> names = ImmutableSet.builder();
        for (Class<?> clazz = getClass(); clazz != CDI.class; clazz = clazz.getSuperclass()) {
            names.add(clazz.getName());
        }
        names.add(Unmanaged.class.getName());
        this.knownClassNames = names.build();
        this.instanceCache = ComputingCacheBuilder.newBuilder().<BeanManagerImpl, Instance<T>> build(
                (b) -> cast(b.getInstance(b.createCreationalContext(null))));
    }

    @Override
    public Iterator<T> iterator() {
        checkState();
        return getInstance().iterator();
    }

    @Override
    public T get() {
        checkState();
        return getInstance().get();
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        checkState();
        return getInstance().select(qualifiers);
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        checkState();
        return getInstance().select(subtype, qualifiers);
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        checkState();
        return getInstance().select(subtype, qualifiers);
    }

    @Override
    public boolean isUnsatisfied() {
        checkState();
        return getInstance().isUnsatisfied();
    }

    @Override
    public boolean isAmbiguous() {
        checkState();
        return getInstance().isAmbiguous();
    }

    @Override
    public void destroy(T instance) {
        checkState();
        getInstance().destroy(instance);
    }

    /**
     * Examines {@link StackTraceElement}s to figure out which class invoked a method on {@link CDI}.
     */
    protected String getCallingClassName() {
        boolean outerSubclassReached = false;
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            // the method call that leads to the first invocation of this class or its subclass is considered the caller
            if (!knownClassNames.contains(element.getClassName())) {
                if (outerSubclassReached) {
                    return element.getClassName();
                }
            } else {
                outerSubclassReached = true;
            }
        }
        throw BeanManagerLogger.LOG.unableToIdentifyBeanManager();
    }

    /**
     * Subclasses are allowed to override the default behavior, i.e. to cache an instance per BeanManager.
     *
     * @return the {@link Instance} the relevant calls are delegated to
     */
    protected Instance<T> getInstance() {
        return instanceCache.getValue(BeanManagerProxy.unwrap(getBeanManager()));
    }

    /**
     * Check whether container is running
     */
    protected void checkState(){
      //no-op
    }

}
