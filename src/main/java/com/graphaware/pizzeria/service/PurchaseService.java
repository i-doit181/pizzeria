package com.graphaware.pizzeria.service;

import com.graphaware.pizzeria.model.Pizza;
import com.graphaware.pizzeria.model.PizzeriaUser;
import com.graphaware.pizzeria.model.Purchase;
import com.graphaware.pizzeria.model.PurchaseState;
import com.graphaware.pizzeria.repository.PizzeriaUserRepository;
import com.graphaware.pizzeria.repository.PurchaseRepository;
import com.graphaware.pizzeria.security.PizzeriaUserPrincipal;

import javax.transaction.Transactional;
import java.util.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class PurchaseService {

	// cache for the ongoing order
	private final Map<PizzeriaUser, Purchase> ongoingPurchases;

	private final PurchaseRepository purchaseRepository;

	public PurchaseService(PurchaseRepository purchaseRepository) {
		this.ongoingPurchases = new HashMap<>();
		this.purchaseRepository = purchaseRepository;

		fillUpCacheOnStart();
	}

	@PreAuthorize("hasAuthority('ADD_PIZZA')")
	@Transactional
	public Purchase addPizzaToPurchase(Pizza pizza) {
		PizzeriaUser currentUser = getCurrentUser();

		List<Purchase> purchases = purchaseRepository.findAllByStateEqualsAndCustomer_Id(PurchaseState.DRAFT, currentUser.getId());
		if (purchases.size() > 1) {
			throw new PizzeriaException();
		}
		Purchase purchase;
		if (purchases.isEmpty()) {
			purchase = new Purchase();
			purchase.setCustomer(currentUser);
			purchase.setState(PurchaseState.DRAFT);
		} else {
			purchase = purchases.get(0);
		}
		if (purchase.getPizzas() == null) {
			purchase.setPizzas(new LinkedList<>());
		}
		purchase.setCreationDate(new Date());
		purchase.getPizzas().add(pizza);
		purchaseRepository.save(purchase);
		return purchase;
	}

	@PreAuthorize("hasAuthority('CONFIRM_PURCHASE')")
	public void confirmPurchase() {
		PizzeriaUser currentUser = getCurrentUser();
		List<Purchase> purchases = purchaseRepository.findAllByStateEqualsAndCustomer_Id(PurchaseState.DRAFT, currentUser.getId());
		if (purchases.size() != 1) {
			throw new PizzeriaException();
		}
		Purchase purchase = purchases.get(0);
		purchase.setState(PurchaseState.PLACED);
		purchaseRepository.save(purchase);
	}

	@PreAuthorize("hasAuthority('PICK_PURCHASE')")
	public Purchase pickPurchase() {
		PizzeriaUser currentUser = getCurrentUser();
		Purchase purchase = purchaseRepository.findFirstByStateEquals(PurchaseState.PLACED);
		if (purchase != null) {
			purchase.setWorker(currentUser);
			purchase.setState(PurchaseState.ONGOING);
			// can work only on a single order!
			if (ongoingPurchases.containsKey(currentUser)) {
				throw new PizzeriaException();
			}
			ongoingPurchases.put(currentUser, purchase);
			return purchaseRepository.save(purchase);
		}

		return null;
	}

	@PreAuthorize("hasAuthority('PIZZA_MAKER')")
	public Purchase getCurrentPurchase() {
		return ongoingPurchases.get(getCurrentUser());
	}

	@PreAuthorize("hasAuthority('PIZZA_MAKER')")
	public void completePurchase(long id) {
		PizzeriaUser currentUser = getCurrentUser();

		Purchase purchase = purchaseRepository.findById(id).orElseThrow(PizzeriaException::new);

		if (!purchase.getState().equals(PurchaseState.ONGOING)) {
			throw new PizzeriaException();
		}
		if (ongoingPurchases.get(currentUser).getId() != purchase.getId()) {
			throw new PizzeriaException();
		}
		purchase.setCheckoutDate(new Date());
		purchase.setState(PurchaseState.SERVED);
		purchase.setAmount(computeAmount(purchase.getPizzas()));
		purchaseRepository.save(purchase);
		ongoingPurchases.remove(currentUser);

		try {
			new EmailService().sendConfirmationEmail(currentUser, purchase);
		} catch (Exception e) {}
	}

	private Double computeAmount(List<Pizza> pizzas) {
		double totalPrice;
		if (pizzas == null) {
			return 0.0;
		}

		// reality: customer can have only one discount rule applied.
		if (hasThreePizzasDiscount(pizzas)) {
			totalPrice = calculateThreePizzasDiscount(pizzas);
		} else if (hasPineappleDiscount(pizzas)) {
			totalPrice = calculatePineappleDiscount(pizzas);
		} else {
			totalPrice = pizzas.stream().mapToDouble(Pizza::getPrice).sum();
		}
		return totalPrice;
	}

	private double calculateThreePizzasDiscount(List<Pizza> pizzas) {

		// find pizza with minimum price. If two pizzas have the same price, the first one is returned.
		Optional<Pizza> maybeAPizza = pizzas.stream().min(Comparator.comparingDouble(Pizza::getPrice));
		Pizza firstCheapestPizza;

		if (maybeAPizza.isPresent()) {
			firstCheapestPizza = maybeAPizza.get();
		} else {
			throw new IllegalArgumentException("Pizza was not found! Purchase is illegal");
		}

		return pizzas.stream().mapToDouble(Pizza::getPrice).sum() - firstCheapestPizza.getPrice();
	}

	private boolean hasThreePizzasDiscount(List<Pizza> pizzas) {
		// only if customer has exactly 3 pizzas.
		return pizzas.size() == 3;
	}

	private boolean hasPineappleDiscount(List<Pizza> pizzas) {
		// buy a pineapple pizza, get 10% off the others
		boolean applyPineappleDiscount = false;
		for (Pizza pizza : pizzas) {
			if (pizza.getToppings().contains("pineapple")) {
				applyPineappleDiscount = true;
				break;
			}
		}

		return applyPineappleDiscount;
	}

	private double calculatePineappleDiscount(List<Pizza> pizzas) {
		double totalPrice = 0;

		for (Pizza pizza : pizzas) {
			if (pizza.getToppings() != null && pizza.getToppings().contains("pineapple")) {
				totalPrice += pizza.getPrice();
			} else {
				totalPrice += pizza.getPrice() * 0.9;
			}
		}
		return totalPrice;
	}

	private void fillUpCacheOnStart() {
		List<Purchase> placedPurchase = purchaseRepository.findAllByStateEquals(PurchaseState.ONGOING);
		for (Purchase ongoingPurchase : placedPurchase) {
			// ongoing state has always a worker
			ongoingPurchases.put(ongoingPurchase.getWorker(), ongoingPurchase);
		}
	}

	private PizzeriaUser getCurrentUser() {
		return ((PizzeriaUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUser();
	}
}
