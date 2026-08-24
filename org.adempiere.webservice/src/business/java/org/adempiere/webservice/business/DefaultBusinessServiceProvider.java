package org.adempiere.webservice.business;

import java.util.Objects;
import java.util.function.Supplier;

import org.adempiere.webservice.ServiceRequestContext;

public final class DefaultBusinessServiceProvider
		implements BusinessServiceProvider {

	private static final String AD_SERVICE_SESSION_KEY =
			DefaultBusinessServiceProvider.class.getName() + ".ADService";

	private final Supplier<? extends ADServiceBusiness> adServiceFactory;
	private final Supplier<? extends ModelADServiceBusiness> modelServiceFactory;
	private final Supplier<? extends ExternalSalesService> externalSalesFactory;
	private final Supplier<? extends CustomerService> customerServiceFactory;

	public DefaultBusinessServiceProvider() {
		this(
				DefaultADService::new,
				DefaultModelADService::new,
				DefaultExternalSalesService::new,
				DefaultCustomerService::new);
	}

	public DefaultBusinessServiceProvider(
			Supplier<? extends ADServiceBusiness> adServiceFactory,
			Supplier<? extends ModelADServiceBusiness> modelServiceFactory,
			Supplier<? extends ExternalSalesService> externalSalesFactory,
			Supplier<? extends CustomerService> customerServiceFactory) {
		this.adServiceFactory =
				Objects.requireNonNull(adServiceFactory, "adServiceFactory");
		this.modelServiceFactory =
				Objects.requireNonNull(modelServiceFactory, "modelServiceFactory");
		this.externalSalesFactory =
				Objects.requireNonNull(externalSalesFactory, "externalSalesFactory");
		this.customerServiceFactory =
				Objects.requireNonNull(customerServiceFactory, "customerServiceFactory");
	}

	@Override
	public ADServiceBusiness getADService(ServiceRequestContext context) {
		return (ADServiceBusiness) context.getOrCreateSessionValue(
				AD_SERVICE_SESSION_KEY, adServiceFactory);
	}

	@Override
	public ModelADServiceBusiness getModelADService() {
		return modelServiceFactory.get();
	}

	@Override
	public ExternalSalesService getExternalSalesService() {
		return externalSalesFactory.get();
	}

	@Override
	public CustomerService getCustomerService() {
		return customerServiceFactory.get();
	}
}
