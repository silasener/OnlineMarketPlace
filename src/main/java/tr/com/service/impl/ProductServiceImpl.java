package tr.com.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.com.dto.ProductDto;
import tr.com.dto.SellerDto;
import tr.com.exception.*;
import tr.com.mapper.ProductMapper;
import tr.com.mapper.SellerMapper;
import tr.com.model.Product;
import tr.com.model.Seller;
import tr.com.model.User;
import tr.com.repository.ProductRepository;
import tr.com.repository.SellerRepository;
import tr.com.repository.UserRepository;
import tr.com.request.CreateNewProductRequest;
import tr.com.request.ProductFilterRequest;
import tr.com.request.UpdateExistingProductRequest;
import tr.com.service.ProductService;
import tr.com.utils.StringUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ProductServiceImpl implements ProductService {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final SellerRepository sellerRepository;

    private final ProductMapper productMapper;
    private final SellerMapper sellerMapper;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, Object> getAvailableProductsForUser(String userId, int page, int size) {
        final User user = userRepository.findUserById(UUID.fromString(userId)).orElseThrow(() -> new UserNotFoundException(userId));

        Pageable pageable = PageRequest.of(page, size);

        Page<Product> newProductsPage;
        if (user.getBlackListedSellers().isEmpty()) {
            newProductsPage = productRepository.findBySellersIsNotEmpty(pageable);
        } else {
            newProductsPage = productRepository.findProductsByNonBlackListedSellers(user.getBlackListedSellers(), pageable);
        }

        List<ProductDto> productDtoList = newProductsPage.getContent().stream().map(productMapper::toDto).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("products", productDtoList);
        response.put("totalPages", newProductsPage.getTotalPages());

        return response;
    }

    @Override
    public Map<String, Object> getAllProducts(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> productPage = productRepository.findAll(pageable);

        List<ProductDto> productDtoList = productPage.getContent().stream().map(productMapper::toDto).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("products", productDtoList);
        response.put("totalPages", productPage.getTotalPages());
        return response;
    }


    @Transactional
    @Override
    public ProductDto createNewProduct(CreateNewProductRequest createNewProductRequest) {
        final ProductDto productDto = ProductDto.builder().id(UUID.randomUUID().toString())
                .name(createNewProductRequest.getName())
                .brand(createNewProductRequest.getBrand())
                .category(createNewProductRequest.getCategory())
                .imageUrl(createNewProductRequest.getImageUrl())
                .build();

        Optional<Product> existingProduct = productRepository.findProductByNameAndBrandAndCategory(productDto.getName(), productDto.getBrand(), productDto.getCategory());
        if (existingProduct.isPresent()) {
            Product product = existingProduct.get();
            throw new ProductAlreadyExistingException(String.valueOf(product.getId()));
        } else {
            final Product product = productMapper.toModel(productDto);
            return productMapper.toDto(productRepository.save(product));
        }
    }


    @Override
    public ProductDto deleteProductById(String productId) {
        final Product product = productRepository.findProductById(UUID.fromString(productId)).orElseThrow(() -> new ProductNotFoundException(productId));

        for (User user : product.getFavoriteByUsers()) {
            user.getFavoriteProducts().remove(product);
        }

        for (Seller seller : product.getSellers()) {
            seller.getProducts().remove(product);
        }

        productRepository.delete(product);
        return productMapper.toDto(product);
    }

    @Override
    public ProductDto updateExistingProduct(String productId, UpdateExistingProductRequest updateExistingProductRequest) {
        ProductDto updateProductDto = ProductDto.builder().name(updateExistingProductRequest.getName())
                .category(updateExistingProductRequest.getCategory())
                .brand(updateExistingProductRequest.getBrand())
                .imageUrl(updateExistingProductRequest.getImageUrl())
                .build();

        final Product product = productRepository.findProductById(UUID.fromString(productId)).orElseThrow(() -> new ProductNotFoundException(productId));

        Optional<Product> productExisting = productRepository.findProductByNameAndBrandAndCategoryAndIdNot(updateProductDto.getName(), updateProductDto.getBrand(), updateProductDto.getCategory(), product.getId());

        if (productExisting.isPresent()) {
            throw new ProductAlreadyExistingException(String.valueOf(productExisting.get().getId()));
        }

        if (StringUtils.isNotBlank(updateProductDto.getName())) {
            product.setName(updateProductDto.getName());
        }
        if (StringUtils.isNotBlank(String.valueOf(updateProductDto.getCategory()))) {
            product.setCategory(updateProductDto.getCategory());
        }
        if (StringUtils.isNotBlank(String.valueOf(updateProductDto.getBrand()))) {
            product.setBrand(updateProductDto.getBrand());
        }
        if (StringUtils.isNotBlank(String.valueOf(updateProductDto.getImageUrl()))) {
            product.setImageUrl(updateProductDto.getImageUrl());
        }

        return productMapper.toDto(productRepository.save(product));
    }

    @Override
    public List<SellerDto> getSellersByProductId(String productId) {
        final Product product = productRepository.findProductById(UUID.fromString(productId)).orElseThrow(() -> new ProductNotFoundException(productId));

        final List<Seller> sellerList = sellerRepository.findAll().stream().filter(seller -> seller.getProducts().contains(product)).toList();

        return sellerList.stream().map(sellerMapper::toDto).collect(Collectors.toList());
    }

    @Override
    public ProductDto getProductById(String productId) {
        final Product product = productRepository.findProductById(UUID.fromString(productId)).orElseThrow(() -> new ProductNotFoundException(productId));
        return productMapper.toDto(product);
    }

    @Override
    public List<ProductDto> getProductsBySellerId(String sellerId) {
        final Seller seller = sellerRepository.findSellerById(UUID.fromString(sellerId)).orElseThrow(() -> new SellerNotFoundException(sellerId));

        return seller.getProducts().stream().map(productMapper::toDto).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> filterAvailableProductsForUser(ProductFilterRequest productFilterRequest, int page, int size) {
        final User user = userRepository.findUserById(UUID.fromString(productFilterRequest.getUserId())).orElseThrow(() -> new UserNotFoundException(productFilterRequest.getUserId()));

        Pageable pageable = PageRequest.of(page, size);

        Page<Product> paginatedProducts = productRepository.findFilteredProductsForUser(
                user.getBlackListedSellers(),
                productFilterRequest.getProductNames().isEmpty() ? null : productFilterRequest.getProductNames(),
                productFilterRequest.getCategories().isEmpty() ? null : productFilterRequest.getCategories(),
                productFilterRequest.getBrands().isEmpty() ? null : productFilterRequest.getBrands(),
                pageable
        );

        if (paginatedProducts.isEmpty()) {
            throw new ProductNotFoundForFilterException();
        }

        List<ProductDto> filteredProductDto = paginatedProducts.stream().map(productMapper::toDto).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("products", filteredProductDto);
        response.put("totalPages", paginatedProducts.getTotalPages());
        return response;
    }

    @Override
    public Map<String, Object> filterProducts(ProductFilterRequest productFilterRequest, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        Page<Product> paginatedFilteredProducts = productRepository.findFilteredProducts(
                productFilterRequest.getProductNames().isEmpty() ? null : productFilterRequest.getProductNames(),
                productFilterRequest.getCategories().isEmpty() ? null : productFilterRequest.getCategories(),
                productFilterRequest.getBrands().isEmpty() ? null : productFilterRequest.getBrands(),
                pageable
        );

        if (paginatedFilteredProducts.isEmpty()) {
            throw new ProductNotFoundForFilterException();
        }

        List<ProductDto> filteredProductDto = paginatedFilteredProducts.stream().map(productMapper::toDto).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("products", filteredProductDto);
        response.put("totalPages", paginatedFilteredProducts.getTotalPages());

        return response;
    }

    @Override
    public void checkAndLoadSqlData() throws IOException {
        if(sellerRepository.findAll().isEmpty() && productRepository.findAll().isEmpty()){
            String filePath = "src/main/resources/insert_queries.sql";
            List<String> queries = Files.readAllLines(Paths.get(filePath));

            for (String query : queries) {
                if (!query.trim().isEmpty()) {
                    jdbcTemplate.execute(query);
                }
            }
        }
    }


}
