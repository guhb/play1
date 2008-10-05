package play.classloading.enhancers;

import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import play.Logger;
import play.classloading.ApplicationClasses.ApplicationClass;

/**
 * Enhance JPAModel entities classes
 */
public class JPAEnhancer extends Enhancer {

    public void enhanceThisClass(ApplicationClass applicationClass) throws Exception {
        CtClass ctClass = makeClass(applicationClass);

        if (!ctClass.subtypeOf(classPool.get("play.db.jpa.JPAModel"))) {
            return;
        }
        // les classes non entity ne doivent pas etre instrumentees
         if (!hasAnnotation(ctClass, "javax.persistence.Entity")) {
            return;
        }
        String entityName = ctClass.getSimpleName();
        Logger.trace("Enhancing " + entityName);
        // Ajoute le constructeur par défaut (obligatoire pour la peristence)
        try {
            boolean hasDefaultConstructor = false;
            for (CtConstructor constructor : ctClass.getConstructors()) {
                if (constructor.getParameterTypes().length == 0) {
                    hasDefaultConstructor = true;
                    break;
                }
            }
            if (!hasDefaultConstructor && !ctClass.isInterface()) {
                CtConstructor defaultConstructor = CtNewConstructor.make("private " + ctClass.getSimpleName() + "() {}", ctClass);
                ctClass.addConstructor(defaultConstructor);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // count
        CtMethod count = CtMethod.make("public static Long count() { return (Long) getEntityManager().createQuery(\"select count(*) from " + ctClass.getName() + "\").getSingleResult(); }", ctClass);
        ctClass.addMethod(count);
        
        // count2
        CtMethod count2 = CtMethod.make("public static Long count(String query, Object[] params) { return (Long) bindParameters(getEntityManager().createQuery(createCountQuery(\"" + ctClass.getSimpleName() + "\", \"" + ctClass.getName() + "\", query, params)), params).getSingleResult(); }", ctClass);
        ctClass.addMethod(count2);


        // findAll
        CtMethod findAll = CtMethod.make("public static java.util.List findAll() { return getEntityManager().createQuery(\"select e from " + entityName + " e\").getResultList();}", ctClass);
        ctClass.addMethod(findAll);

        // findById
        CtMethod findById = CtMethod.make("public static play.db.jpa.JPAModel findById(Long id) { return (" + ctClass.getName() + ") getEntityManager().find(" + ctClass.getName() + ".class, id); }", ctClass);
        ctClass.addMethod(findById);

        // findBy        
        CtMethod findBy = CtMethod.make("public static java.util.List findBy(String query, Object[] params) { javax.persistence.Query q = getEntityManager().createQuery(createFindByQuery(\"" + ctClass.getSimpleName() + "\", \"" + ctClass.getName() + "\", query, params)); return bindParameters(q,params).getResultList(); }", ctClass);
        ctClass.addMethod(findBy);
        
        // query        
        CtMethod query = CtMethod.make("public static play.db.jpa.JPAModel.JPAQuery query(String query, Object[] params) { javax.persistence.Query q = getEntityManager().createQuery(createFindByQuery(\"" + ctClass.getSimpleName() + "\", \"" + ctClass.getName() + "\", query, params)); return new play.db.jpa.JPAModel.JPAQuery(bindParameters(q,params)); }", ctClass);
        ctClass.addMethod(query);

        // findOneBy
        CtMethod findOneBy = CtMethod.make("public static play.db.jpa.JPAModel findOneBy(String query, Object[] params) { javax.persistence.Query q = getEntityManager().createQuery(createFindByQuery(\"" + ctClass.getSimpleName() + "\", \"" + ctClass.getName() + "\", query, params)); try { return (" + ctClass.getName() + ") bindParameters(q,params).getSingleResult();} catch (javax.persistence.NoResultException e) { return null;} }", ctClass);
        ctClass.addMethod(findOneBy);

        applicationClass.enhancedByteCode = ctClass.toBytecode();
        ctClass.defrost();
    }

}
