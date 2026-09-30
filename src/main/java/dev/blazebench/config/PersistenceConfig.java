package dev.blazebench.config;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.integration.view.spring.EnableEntityViews;
import com.blazebit.persistence.spring.data.repository.config.EnableBlazeRepositories;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.spi.EntityViewConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Plain Spring Data JPA repositories (the baseline) and Blaze-Persistence repositories
 * live in separate packages so each is created by its own factory.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = "dev.blazebench.jpa")
@EnableBlazeRepositories(basePackages = "dev.blazebench.blaze")
@EnableEntityViews(basePackages = { "dev.blazebench.blaze", "dev.blazebench.projection.blaze" })
public class PersistenceConfig {

	@Bean
	CriteriaBuilderFactory criteriaBuilderFactory(EntityManagerFactory entityManagerFactory) {
		return Criteria.getDefault().createCriteriaBuilderFactory(entityManagerFactory);
	}

	@Bean
	EntityViewManager entityViewManager(CriteriaBuilderFactory criteriaBuilderFactory,
			EntityViewConfiguration entityViewConfiguration) {
		return entityViewConfiguration.createEntityViewManager(criteriaBuilderFactory);
	}

}
