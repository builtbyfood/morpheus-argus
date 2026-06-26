package com.morpheusdata.iloconsole.services

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.AccountCredential
import groovy.util.logging.Slf4j

/**
 * Resolves an AccountCredential to plaintext username/password.
 *
 * The Morpheus 9.0 / plugin-api 1.3.1 credential API has two known broken paths:
 *   - services.get(Long) / find(DataQuery) wrap async.get/find which return Maybe;
 *     the framework's internal cast Maybe→Single throws GroovyCastException.
 *   - All other "Maybe" methods are similarly broken.
 *
 * The working flow proven through v0.1.13:
 *   1. services.listById([credentialId])  ← returns List<AccountCredential> directly
 *   2. services.loadCredentialConfig([id: N, type: 1], [:])  ← returns ServiceResponse
 *   3. Extract result.data.username + result.data.password from the envelope.
 *
 * Returns Map with [username, password, source] on success, or [error, attempts] on failure.
 */
@Slf4j
class IloCredentialService {

    /** Resolve credentialId to plaintext credentials. */
    static Map loadCredential(MorpheusContext morpheusContext, Long credentialId) {
        if (credentialId == null) return [error: 'No credential id']

        AccountCredential ac = null
        List<String> attemptLog = []
        String source = null

        // ── Fetch the AccountCredential via sync.listById([Long]) ────────
        try {
            def svc = morpheusContext.services?.accountCredential
            if (svc != null) {
                java.lang.reflect.Method m = svc.class.methods.find {
                    it.name == 'listById' && it.parameterCount == 1
                }
                if (m != null) {
                    Object result = m.invoke(svc, [credentialId])
                    if (result instanceof Collection && !((Collection) result).isEmpty()) {
                        Object first = ((Collection) result).find {
                            it instanceof AccountCredential && (it as AccountCredential).id == credentialId
                        } ?: ((Collection) result).iterator().next()
                        if (first instanceof AccountCredential) {
                            ac = first as AccountCredential
                            source = 'services.listById'
                        }
                    }
                }
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            attemptLog << "listById threw: ${(ite.cause ?: ite).message}"
        } catch (Throwable t) {
            attemptLog << "listById: ${t.message}"
        }

        if (ac == null) {
            return [error: "Credential ${credentialId} not found", attempts: attemptLog]
        }

        // ── Decrypt via loadCredentialConfig([id: N, type: 1], [:]) ──────
        String username = null
        String password = null
        try {
            def svc = morpheusContext.services?.accountCredential
            java.lang.reflect.Method lcc = svc?.class?.methods?.find {
                it.name == 'loadCredentialConfig' && it.parameterCount == 2
            }
            if (lcc != null) {
                Object res = lcc.invoke(svc, [id: credentialId, type: 1], [:])
                if (res instanceof Map) {
                    Map env = res as Map
                    Map data = (env.data instanceof Map) ? (env.data as Map) : env
                    username = data.username as String
                    password = (data.password ?: data.passwd) as String
                    if (password) source = "${source}+lcc"
                }
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            attemptLog << "lcc threw: ${(ite.cause ?: ite).message}"
        } catch (Throwable t) {
            attemptLog << "lcc: ${t.message}"
        }

        if (!username || !password) {
            return [error: "Credential ${credentialId} (name='${ac.name}') loaded but missing username/password",
                    attempts: attemptLog, source: source]
        }

        log.info("loadCredential ${credentialId} (name='${ac.name}') ok via ${source}")
        return [username: username, password: password, source: source, credentialName: ac.name]
    }
}
