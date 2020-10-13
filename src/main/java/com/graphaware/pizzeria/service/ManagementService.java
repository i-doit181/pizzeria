package com.graphaware.pizzeria.service;

import com.graphaware.pizzeria.model.PizzeriaUser;
import com.graphaware.pizzeria.model.UserRole;
import com.graphaware.pizzeria.repository.PizzeriaUserRepository;
import com.graphaware.pizzeria.repository.PurchaseRepository;
import com.graphaware.pizzeria.security.PizzeriaUserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ManagementService {
	private final PurchaseRepository purchaseRepository;

	public ManagementService(PurchaseRepository purchaseRepository) {
		this.purchaseRepository = purchaseRepository;
	}

	public long getPurchasesCount() {
		PizzeriaUser currentUser = getCurrentUser();
		if (currentUser.getRoles().stream().noneMatch(userRole -> userRole.equals(UserRole.OWNER))) {
			throw new AccessDeniedException("Access not allowed");
		}
		return purchaseRepository.count();
	}

	private PizzeriaUser getCurrentUser() {
		// todo change current user
		// return pizzeriaUserRepository.findById(66L).orElseThrow(IllegalArgumentException::new);
		return ((PizzeriaUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUser();
	}
}
