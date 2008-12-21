/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
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

package org.jboss.webbeans.bean;

import java.util.HashSet;
import java.util.Set;

import javax.webbeans.ApplicationScoped;
import javax.webbeans.Decorator;
import javax.webbeans.DefinitionException;
import javax.webbeans.Dependent;
import javax.webbeans.Destructor;
import javax.webbeans.Disposes;
import javax.webbeans.Initializer;
import javax.webbeans.Interceptor;
import javax.webbeans.Observes;
import javax.webbeans.Produces;
import javax.webbeans.Specializes;
import javax.webbeans.manager.Manager;

import org.jboss.webbeans.ManagerImpl;
import org.jboss.webbeans.bootstrap.spi.EjbDescriptor;
import org.jboss.webbeans.bootstrap.spi.MethodDescriptor;
import org.jboss.webbeans.contexts.DependentContext;
import org.jboss.webbeans.ejb.DefaultEnterpriseBeanLookup;
import org.jboss.webbeans.introspector.AnnotatedField;
import org.jboss.webbeans.introspector.AnnotatedMethod;
import org.jboss.webbeans.introspector.AnnotatedParameter;
import org.jboss.webbeans.log.LogProvider;
import org.jboss.webbeans.log.Logging;

/**
 * An enterprise bean representation
 * 
 * @author Pete Muir
 * 
 * @param <T>
 */
public class EnterpriseBean<T> extends AbstractClassBean<T>
{
   private LogProvider log = Logging.getLogProvider(EnterpriseBean.class);
   
   // The EJB descriptor
   private EjbDescriptor<T> ejbDescriptor;
   
   // The remove method on the bean class (do not call!)
   private AnnotatedMethod<?> removeMethod;
   

   /**
    * Constructor
    * 
    * @param type The type of the bean
    * @param manager The Web Beans manager
    */
   public EnterpriseBean(Class<T> type, ManagerImpl manager)
   {
      super(type, manager);
      init();
   }

   /**
    * Initializes the bean and its metadata
    */
   @Override
   protected void init()
   {
      super.init();
      Iterable<EjbDescriptor<T>> ejbDescriptors = manager.getEjbDescriptorCache().get(getType());
      if (ejbDescriptors == null)
      {
         throw new DefinitionException("Not an EJB " + toString());
      }
      for (EjbDescriptor<T> ejbDescriptor : ejbDescriptors)
      {
         if (this.ejbDescriptor == null)
         {
            this.ejbDescriptor = ejbDescriptor;
         }
         else
         {
            throw new RuntimeException("TODO Multiple EJBs have the same bean class! " + getType() );
         }
      }
      initRemoveMethod();
      initInjectionPoints();
      checkEnterpriseBeanTypeAllowed();
      checkEnterpriseScopeAllowed();
      checkConflictingRoles();
      checkSpecialization();
      checkRemoveMethod();
   }

   /**
    * Initializes the injection points
    */
   @Override
   protected void initInjectionPoints()
   {
      super.initInjectionPoints();
      if (removeMethod != null)
      {
         for (AnnotatedParameter<?> injectable : removeMethod.getParameters())
         {
            injectionPoints.add(injectable);
         }
      }
   }

   /**
    * Validates for non-conflicting roles
    */
   protected void checkConflictingRoles()
   {
      if (getType().isAnnotationPresent(Interceptor.class))
      {
         throw new DefinitionException("Enterprise beans cannot be interceptors");
      }
      if (getType().isAnnotationPresent(Decorator.class))
      {
         throw new DefinitionException("Enterprise beans cannot be decorators");
      }
   }

   /**
    * Check that the scope type is allowed by the stereotypes on the bean and
    * the bean type
    */
   protected void checkEnterpriseScopeAllowed()
   {
      if (ejbDescriptor.isStateless() && !getScopeType().equals(Dependent.class))
      {
         throw new DefinitionException("Scope " + getScopeType() + " is not allowed on stateless enterpise beans for " + getType() + ". Only @Dependent is allowed on stateless enterprise beans");
      }
      if (ejbDescriptor.isSingleton() && (!(getScopeType().equals(Dependent.class) || getScopeType().equals(ApplicationScoped.class))))
      {
         throw new DefinitionException("Scope " + getScopeType() + " is not allowed on singleton enterpise beans for " + getType() + ". Only @Dependent or @ApplicationScoped is allowed on singleton enterprise beans");
      }
   }

   /**
    * Validates specialization
    */
   private void checkSpecialization()
   {
      if (!getType().isAnnotationPresent(Specializes.class))
      {
         return;
      }
      // TODO Should also check the bean type it does contain!
      if (!manager.getEjbDescriptorCache().containsKey(getType().getSuperclass()))
      {
         throw new DefinitionException("Annotation defined specializing EJB must have EJB superclass");
      }
   }

   /**
    * Initializes the remvoe method
    */
   protected void initRemoveMethod()
   {

      // >1 @Destructor
      if (getAnnotatedItem().getAnnotatedMethods(Destructor.class).size() > 1)
      {
         throw new DefinitionException("Multiple @Destructor methods not allowed on " + getAnnotatedItem());
      }
      
      if (getAnnotatedItem().getAnnotatedMethods(Destructor.class).size() == 1)
      {
         AnnotatedMethod<?> destructorMethod = getAnnotatedItem().getAnnotatedMethods(Destructor.class).iterator().next();
         for (MethodDescriptor removeMethod : ejbDescriptor.getRemoveMethods())
         {
            AnnotatedMethod<?> annotatedRemoveMethod = getAnnotatedItem().getMethod(removeMethod);
            if (annotatedRemoveMethod != null && annotatedRemoveMethod.equals(destructorMethod))
            {
               this.removeMethod = destructorMethod;
               return;
            }
         }
         throw new DefinitionException("Method annotated @Destructor is not an EJB remove method on " + toString());
      }
      // <1 (0) @Destructors
      Set<MethodDescriptor> noArgsRemoveMethods = getNoArgsRemoveMethods(ejbDescriptor);
      if (noArgsRemoveMethods.size() == 1)
      {
         this.removeMethod = annotatedItem.getMethod(noArgsRemoveMethods.iterator().next());
         return;
      }
      
      if (!getScopeType().equals(Dependent.class))
      {
         throw new DefinitionException("Only @Dependent scoped enterprise beans can be without remove methods " + toString());
      }

   }

   private static Set<MethodDescriptor> getNoArgsRemoveMethods(EjbDescriptor<?> ejbDescriptor)
   {
      Set<MethodDescriptor> noArgsRemoveMethods = new HashSet<MethodDescriptor>();
      for (MethodDescriptor removeMethod : ejbDescriptor.getRemoveMethods())
      {
         if (removeMethod.getMethodParameterTypes().length == 0)
         {
            noArgsRemoveMethods.add(removeMethod);
         }
      }
      return noArgsRemoveMethods;
   }
   
   /**
    * Validates the remove method
    */
   private void checkRemoveMethod()
   {
      if (removeMethod == null)
      {
         return;
      }
      else if (ejbDescriptor.isStateless())
      {
         throw new DefinitionException("Can't define a remove method on SLSBs");
      }
      if (removeMethod.isAnnotationPresent(Initializer.class))
      {
         throw new DefinitionException("Remove methods cannot be initializers on " + removeMethod.getName());
      }
      else if (removeMethod.isAnnotationPresent(Produces.class))
      {
         throw new DefinitionException("Remove methods cannot be producers on " + removeMethod.getName());
      }
      else if (removeMethod.getAnnotatedParameters(Disposes.class).size() > 0)
      {
         throw new DefinitionException("Remove method can't have @Disposes annotated parameters on " + removeMethod.getName());
      }
      else if (removeMethod.getAnnotatedParameters(Observes.class).size() > 0)
      {
         throw new DefinitionException("Remove method can't have @Observes annotated parameters on " + removeMethod.getName());
      }
   }

   /**
    * Creates an instance of the bean
    * 
    * @return The instance
    */
   @Override
   public T create()
   {
      try
      {
         DependentContext.INSTANCE.setActive(true);
         T instance = (T) manager.getInstanceByType(DefaultEnterpriseBeanLookup.class).lookup(ejbDescriptor);
         // TODO Return enterprise proxy
         return instance;
      }
      finally
      {
         DependentContext.INSTANCE.setActive(false);
      }
   }

   /**
    * Destroys an instance of the bean
    * 
    * @param instance The instance
    */
   @Override
   public void destroy(T instance)
   {
      try
      {
         DependentContext.INSTANCE.setActive(true);
         removeMethod.invokeOnInstance(instance, manager);
      }
      catch (Exception e) 
      {
         log.error("Error destroying " + toString(), e);
      }
      finally
      {
         DependentContext.INSTANCE.setActive(false);
      }
   }

   /**
    * Calls all initializers of the bean
    * 
    * @param instance The bean instance
    */
   protected void callInitializers(T instance)
   {
      for (AnnotatedMethod<Object> initializer : getInitializerMethods())
      {
         initializer.invoke(manager, instance);
      }
   }

   /**
    * Injects EJBs and common fields
    */
   protected void injectEjbAndCommonFields()
   {
      // TODO Support commons and EJB annotations
   }

   /**
    * Injects bound fields
    * 
    * @param instance The bean instance
    * @param manager The Web Beans manager
    */
   protected void injectBoundFields(T instance, Manager manager)
   {
      for (AnnotatedField<?> field : getInjectableFields())
      {
         field.inject(instance, manager);
      }
   }

   /**
    * Gets the specializes type of the bean
    * 
    * @return The specialized type
    */
   @SuppressWarnings("unchecked")
   @Override
   protected AbstractBean<? extends T, Class<T>> getSpecializedType()
   {
      // TODO: lots of validation!
      Class<?> superclass = getAnnotatedItem().getType().getSuperclass();
      if (superclass != null)
      {
         // TODO look up this bean and do this via init
         return new EnterpriseBean(superclass, manager);
      }
      else
      {
         throw new RuntimeException();
      }

   }
   
   public AnnotatedMethod<?> getRemoveMethod()
   {
      return removeMethod;
   }

   /**
    * Validates the bean type
    */
   private void checkEnterpriseBeanTypeAllowed()
   {
      if (ejbDescriptor.isMessageDriven())
      {
         throw new DefinitionException("Message Driven Beans can't be Web Beans");
      }
   }

   /**
    * Gets a string representation
    * 
    * @return The string representation
    */
   @Override
   public String toString()
   {
      StringBuilder buffer = new StringBuilder();
      // buffer.append("Annotated " + Names.scopeTypeToString(getScopeType()) +
      // Names.ejbTypeFromMetaData(getEjbMetaData()));
      if (getName() == null)
      {
         buffer.append(" unnamed enterprise bean");
      }
      else
      {
         buffer.append(" enterprise bean '" + getName() + "'");
      }
      buffer.append(" [" + getType().getName() + "]\n");
      buffer.append("   API types " + getTypes() + ", binding types " + getBindingTypes() + "\n");
      return buffer.toString();
   }

   public void postConstruct(T instance)
   {
      try
      {
         DependentContext.INSTANCE.setActive(true);
         bindDecorators();
         bindInterceptors();
         injectEjbAndCommonFields();
         injectBoundFields(instance, manager);
         callInitializers(instance);
      }
      finally
      {
         DependentContext.INSTANCE.setActive(false);
      }
      
   }

   public void preDestroy(Object target)
   {
      
   }

}
